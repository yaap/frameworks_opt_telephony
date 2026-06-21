/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.telephony;

import static android.service.messaging.AlternativeMessageTransportService.UPGRADE_STATUS_REJECTED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.UserHandle;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

@RunWith(MockitoJUnitRunner.class)
public class MessageUpgradeControllerTest {

    private static final int TEST_USER_ID = 10;
    private static final String TEST_CALLING_PKG = "com.test.messaging";
    private static final Uri TEST_URI = Uri.parse("content://sms/123");

    @Mock private Context mContext;
    @Mock private PackageManager mPackageManager;
    @Mock private MessageUpgradeWorker mMockWorker;
    @Mock private Executor mMockExecutor;
    @Mock private Consumer<Integer> mMockCallback;

    private BroadcastReceiver mRegisteredReceiver;

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Before
    public void setUp() throws Exception {
        resetSingleton();

        when(mContext.getPackageName()).thenReturn("android.telephony.test");
        when(mContext.getPackageManager()).thenReturn(mPackageManager);

        // Capture the receiver registered in the constructor, bypassing ActivityManager
        // Correct Mockito syntax for void methods/complex stubbing
        doAnswer(invocation -> {
            mRegisteredReceiver = invocation.getArgument(0);
            return null;
        }).when(mContext).registerReceiverAsUser(
                any(BroadcastReceiver.class),
                any(UserHandle.class),
                any(IntentFilter.class),
                isNull(),
                isNull()
        );
    }

    @After
    public void tearDown() throws Exception {
        resetSingleton();
    }

    // =========================================================================
    // Null & Empty Inputs / Exception Handling
    // =========================================================================

    @Test
    @SmallTest
    @SuppressWarnings("ConstantConditions")
    public void testIsMessageUpgradeSupported_emptyPackage_throwsIllegalArgumentException() {
        IllegalArgumentException emptyException = assertThrows(IllegalArgumentException.class, () ->
                MessageUpgradeController.isMessageUpgradeSupportedForPackage(
                        mContext, TEST_USER_ID, "", false));
        assertEquals("callingPkg cannot be null or empty", emptyException.getMessage());

        IllegalArgumentException nullException = assertThrows(IllegalArgumentException.class, () ->
                MessageUpgradeController.isMessageUpgradeSupportedForPackage(
                        mContext, TEST_USER_ID, null, false));
        assertEquals("callingPkg cannot be null or empty", nullException.getMessage());
    }

    @Test
    @SmallTest
    @SuppressWarnings("ConstantConditions")
    public void testUpgradeMessage_nullArguments_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () ->
                MessageUpgradeController.upgradeMessage(
                        null, TEST_USER_ID, TEST_URI, null, null,
                        mMockExecutor, mMockCallback));

        assertThrows(NullPointerException.class, () ->
                MessageUpgradeController.upgradeMessage(
                        mContext, TEST_USER_ID, null, null, null,
                        mMockExecutor, mMockCallback));

        assertThrows(NullPointerException.class, () ->
                MessageUpgradeController.upgradeMessage(
                        mContext, TEST_USER_ID, TEST_URI, null, null,
                        null, mMockCallback));
    }

    // =========================================================================
    // Worker Resolution & Fallback Logic
    // =========================================================================

    @Test
    @SmallTest
    @SuppressWarnings("ConstantConditions")
    public void testWorkerResolution_nameNotFoundException_handlesGracefully() throws Exception {
        // Simulate a failure to create the user context
        when(mContext.createPackageContextAsUser(any(), anyInt(), any()))
                .thenThrow(new PackageManager.NameNotFoundException("Test Exception"));

        // Should not crash, should log error and return false
        boolean result = MessageUpgradeController.isMessageUpgradeSupportedForPackage(
                mContext, TEST_USER_ID, TEST_CALLING_PKG, false);

        assertFalse("Should return false when worker fails to instantiate", result);
    }

    @Test
    @SmallTest
    @SuppressWarnings("ConstantConditions")
    public void testUpgradeMessage_workerNotFound_rejectsUpgradeGracefully() throws Exception {
        when(mContext.createPackageContextAsUser(any(), anyInt(),
                any()))
                .thenThrow(new PackageManager.NameNotFoundException());

        // Setup immediate executor
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mMockExecutor).execute(any(Runnable.class));

        MessageUpgradeController.upgradeMessage(mContext, TEST_USER_ID, TEST_URI,
                null, null, mMockExecutor, mMockCallback);

        // Verification: Even though worker creation crashed, we shouldn't crash.
        // We should cleanly reject the upgrade.
        verify(mMockCallback).accept(UPGRADE_STATUS_REJECTED);
    }

    // =========================================================================
    // Happy Path & State Verification
    // =========================================================================

    @Test
    @SmallTest
    public void testIsMessageUpgradeSupported_success_delegatesToWorker() throws Exception {
        injectMockWorker(TEST_USER_ID, mMockWorker);
        when(mMockWorker.isMessageUpgradeSupportedForPackage(TEST_CALLING_PKG, false))
                .thenReturn(true);

        boolean result = MessageUpgradeController.isMessageUpgradeSupportedForPackage(
                mContext, TEST_USER_ID, TEST_CALLING_PKG, false);

        assertTrue(result);
        verify(mMockWorker).isMessageUpgradeSupportedForPackage(TEST_CALLING_PKG, false);
    }

    @Test
    @SmallTest
    @SuppressWarnings("ConstantConditions")
    public void testUpgradeMessage_success_delegatesToWorker() throws Exception {
        injectMockWorker(TEST_USER_ID, mMockWorker);
        List<PendingIntent> sentIntents = new ArrayList<>();
        List<PendingIntent> deliveryIntents = new ArrayList<>();

        MessageUpgradeController.upgradeMessage(mContext, TEST_USER_ID, TEST_URI,
                sentIntents, deliveryIntents, mMockExecutor, mMockCallback);

        verify(mMockWorker).upgradeMessage(eq(TEST_URI), eq(sentIntents),
                eq(deliveryIntents),
                eq(mMockExecutor),
                eq(mMockCallback));
        verify(mMockExecutor, never()).execute(any()); // Should not auto-reject
    }

    @Test
    @SmallTest
    public void testDispatchSmsPendingIntentsIfUpgraded_success_delegatesToWorker()
            throws Exception {
        injectMockWorker(TEST_USER_ID, mMockWorker);
        ContentValues values = new ContentValues();

        MessageUpgradeController.dispatchSmsPendingIntentsIfUpgraded(
                mContext, TEST_USER_ID, TEST_URI, values);

        verify(mMockWorker).dispatchSmsPendingIntentsIfUpgraded(TEST_URI, values);
    }

    @Test
    @SmallTest
    public void testDispatchMmsPendingIntentsIfUpgraded_success_delegatesToWorker()
            throws Exception {
        injectMockWorker(TEST_USER_ID, mMockWorker);
        ContentValues values = new ContentValues();

        MessageUpgradeController.dispatchMmsPendingIntentsIfUpgraded(
                mContext, TEST_USER_ID, TEST_URI, values);

        verify(mMockWorker).dispatchMmsPendingIntentsIfUpgraded(TEST_URI, values);
    }

    // =========================================================================
    // Lifecycle & Clean Up
    // =========================================================================

    @Test
    @SmallTest
    public void testUserRemovedReceiver_cleansUpWorkerAndCloses() throws Exception {
        injectMockWorker(TEST_USER_ID, mMockWorker);
        when(mMockWorker.isMessageUpgradeSupportedForPackage(TEST_CALLING_PKG, false))
                .thenReturn(true);

        MessageUpgradeController.isMessageUpgradeSupportedForPackage(
                mContext, TEST_USER_ID, TEST_CALLING_PKG, false);

        // Simulate broadcast
        Intent intent = new Intent(Intent.ACTION_USER_REMOVED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, TEST_USER_ID);
        mRegisteredReceiver.onReceive(mContext, intent);

        // Verify worker was closed
        verify(mMockWorker).close();
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private void injectMockWorker(int userId, MessageUpgradeWorker worker) throws Exception {
        Field workersField = MessageUpgradeController.class.getDeclaredField("sUpgradeWorkers");
        workersField.setAccessible(true);
        SparseArray<MessageUpgradeWorker> workers =
                (SparseArray<MessageUpgradeWorker>) workersField.get(null);
        workers.put(userId, worker);
    }

    private void resetSingleton() throws Exception {
        Field instanceField = MessageUpgradeController.class.getDeclaredField("sInstance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);

        Field workersField = MessageUpgradeController.class.getDeclaredField("sUpgradeWorkers");
        workersField.setAccessible(true);
        SparseArray<?> workers = (SparseArray<?>) workersField.get(null);
        workers.clear();
    }
}
