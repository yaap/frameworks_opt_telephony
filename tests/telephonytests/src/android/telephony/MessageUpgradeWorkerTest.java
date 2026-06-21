/*
 * Copyright (C) 2026 The Android Open Source Project
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
import static android.telephony.SmsManager.RESULT_ERROR_NO_SERVICE;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.provider.Telephony;
import android.service.messaging.AlternativeMessageTransportServiceWrapper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@RunWith(AndroidJUnit4.class)
public class MessageUpgradeWorkerTest {
    private static final String TEST_DMA_PACKAGE = "com.android.messaging.dma";
    private static final String TEST_CALLING_PACKAGE = "com.android.some.other.app";
    private static final long TEST_MESSAGE_ID = 101L;
    private static final Uri TEST_SMS_URI = Uri.parse("content://sms/" + TEST_MESSAGE_ID);
    private static final Uri TEST_MMS_URI = Uri.parse("content://mms/" + TEST_MESSAGE_ID);
    private static final String ACTION_SENT = "com.test.ACTION_SENT";
    private static final int TIMEOUT_MS = 500;
    private static final String SMS_SENT_INTENT_FIELD = "mSmsPendingSentIntents";
    private static final String SMS_DELIVERY_INTENT_FIELD = "mSmsPendingDeliveryIntents";
    private static final String MMS_SENT_INTENT_FIELD = "mMmsPendingSentIntents";


    @Mock
    private AlternativeMessageTransportServiceWrapper mMockServiceWrapper;
    @Mock
    private Consumer<Integer> mMockClientCallback;

    private Context mContext;
    private AutoCloseable mMockCloseable;
    private MessageUpgradeWorker mWorker;
    private CountDownLatch mLatch;

    private final Executor mSyncExecutor = Runnable::run;
    private final BroadcastReceiver mTestReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mLatch.countDown();
        }
    };

    @Before
    public void setUp() throws Exception {
        mMockCloseable = MockitoAnnotations.openMocks(this);

        mContext = Mockito.spy(ApplicationProvider.getApplicationContext());

        mWorker = new MessageUpgradeWorker(mContext);

        injectMockServiceWrapper(mWorker, mMockServiceWrapper);

        setWorkerCacheState(TEST_DMA_PACKAGE, true);
    }

    @After
    public void tearDown() throws Exception {
        if (mWorker != null) mWorker.close();
        if (mMockCloseable != null) mMockCloseable.close();
    }

    @Test
    @SmallTest
    public void testWorker_registersReceiver() {
        verify(mContext).registerReceiver(any(), any(IntentFilter.class));
    }

    @Test
    @SmallTest
    public void testIsMessageUpgradeSupportedForPackage() {
        // The worker should identify that the DMA supports message upgrade
        assertTrue(mWorker.isMessageUpgradeSupportedForPackage(TEST_CALLING_PACKAGE, false));

        // If the calling package *is* the DMA, it shouldn't upgrade its own messages
        assertFalse(mWorker.isMessageUpgradeSupportedForPackage(TEST_DMA_PACKAGE, false));
    }

    @Test
    @SmallTest
    public void testIsMessageUpgradeSupportedForPackage_nullOrEmptyInput() {
        assertFalse(mWorker.isMessageUpgradeSupportedForPackage(null, false));
        assertFalse(mWorker.isMessageUpgradeSupportedForPackage("", false));
    }

    @Test
    @SmallTest
    public void testUpgradeMessage_success_delegatesToServiceWrapper() {
        mWorker.upgradeMessage(TEST_SMS_URI, null, null, mSyncExecutor,
                mMockClientCallback);

        verify(mMockServiceWrapper).upgradeMessage(
                eq(TEST_SMS_URI),
                eq(TEST_DMA_PACKAGE),
                any(Executor.class),
                any(Consumer.class)
        );
    }

    @Test
    @SmallTest
    public void testUpgradeMessage_invalidUri_rejectsImmediately() throws Exception {
        Uri invalidUri = Uri.parse("content://unknown/abc");
        List<PendingIntent> sentIntents = createPendingIntentSingletonList();
        List<PendingIntent> deliveryIntents = createPendingIntentSingletonList();

        mWorker.upgradeMessage(invalidUri, sentIntents, deliveryIntents, mSyncExecutor,
                mMockClientCallback);

        verify(mMockClientCallback).accept(UPGRADE_STATUS_REJECTED);
        assertCacheIsEmpty(SMS_SENT_INTENT_FIELD);
        assertCacheIsEmpty(SMS_DELIVERY_INTENT_FIELD);
    }

    @Test
    @SmallTest
    public void testUpgradeMessage_notSupported_rejectsImmediately() throws Exception {
        setWorkerCacheState(TEST_DMA_PACKAGE, false);
        List<PendingIntent> sentIntents = createPendingIntentSingletonList();
        List<PendingIntent> deliveryIntents = createPendingIntentSingletonList();

        mWorker.upgradeMessage(TEST_SMS_URI, sentIntents, deliveryIntents, mSyncExecutor,
                mMockClientCallback);

        verify(mMockClientCallback).accept(UPGRADE_STATUS_REJECTED);
        assertCacheIsEmpty(SMS_SENT_INTENT_FIELD);
        assertCacheIsEmpty(SMS_DELIVERY_INTENT_FIELD);
    }

    @Test
    @SmallTest
    public void testDispatchSmsPendingIntentsIfUpgraded_sentComplete()
            throws Exception {

        List<PendingIntent> sentIntents = createPendingIntentSingletonList();
        mWorker.upgradeMessage(TEST_SMS_URI, sentIntents, null, mSyncExecutor,
                mMockClientCallback);

        ContentValues values = new ContentValues();
        values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT);
        try {
            setUpReceiver();

            mWorker.dispatchSmsPendingIntentsIfUpgraded(TEST_SMS_URI, values);

            boolean received = mLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertTrue("Timed out waiting for PendingIntent broadcast", received);
            assertCacheIsEmpty(SMS_SENT_INTENT_FIELD);
        } finally {
            tearDownReceiver();
        }
    }

    @Test
    @SmallTest
    public void testDispatchSmsPendingIntentsIfUpgraded_sentFailed() throws Exception {
        List<PendingIntent> sentIntents = createPendingIntentSingletonList();
        mWorker.upgradeMessage(TEST_SMS_URI, sentIntents, null, mSyncExecutor,
                mMockClientCallback);

        ContentValues values = new ContentValues();
        values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED);
        values.put(Telephony.Sms.ERROR_CODE, RESULT_ERROR_NO_SERVICE);

        try {
            setUpReceiver();

            mWorker.dispatchSmsPendingIntentsIfUpgraded(TEST_SMS_URI, values);

            boolean received = mLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertTrue("Timed out waiting for PendingIntent broadcast on failure", received);
            assertCacheIsEmpty(SMS_SENT_INTENT_FIELD);
        } finally {
            tearDownReceiver();
        }
    }

    @Test
    @SmallTest
    public void testDispatchSmsPendingIntentsIfUpgraded_deliveryComplete() throws Exception {
        List<PendingIntent> deliveryIntents = createPendingIntentSingletonList();
        mWorker.upgradeMessage(TEST_SMS_URI, null, deliveryIntents, mSyncExecutor,
                mMockClientCallback);

        ContentValues values = new ContentValues();
        values.put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE);

        try {
            setUpReceiver();

            mWorker.dispatchSmsPendingIntentsIfUpgraded(TEST_SMS_URI, values);

            boolean received = mLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertTrue("Timed out waiting for Delivery PendingIntent broadcast", received);
            assertCacheIsEmpty(SMS_DELIVERY_INTENT_FIELD);
        } finally {
            tearDownReceiver();
        }
    }

    @Test
    @SmallTest
    public void testDispatchMmsPendingIntentsIfUpgraded_sentComplete() throws Exception {
        List<PendingIntent> sentIntents = createPendingIntentSingletonList();
        mWorker.upgradeMessage(TEST_MMS_URI, sentIntents, null, mSyncExecutor,
                mMockClientCallback);

        ContentValues values = new ContentValues();
        values.put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_SENT);

        try {
            setUpReceiver();

            mWorker.dispatchMmsPendingIntentsIfUpgraded(TEST_MMS_URI, values);

            boolean received = mLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertTrue("Timed out waiting for MMS PendingIntent broadcast", received);
            assertCacheIsEmpty(MMS_SENT_INTENT_FIELD);
        } finally {
            tearDownReceiver();
        }
    }

    @Test
    @SmallTest
    public void testDispatchMmsPendingIntentsIfUpgraded_sentFailed() throws Exception {
        List<PendingIntent> sentIntents = createPendingIntentSingletonList();
        mWorker.upgradeMessage(TEST_MMS_URI, sentIntents, null, mSyncExecutor,
                mMockClientCallback);

        ContentValues values = new ContentValues();
        values.put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_FAILED);

        try {
            setUpReceiver();

            mWorker.dispatchMmsPendingIntentsIfUpgraded(TEST_MMS_URI, values);

            boolean received = mLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertTrue("Timed out waiting for MMS failure PendingIntent broadcast", received);
            assertCacheIsEmpty(MMS_SENT_INTENT_FIELD);
        } finally {
            tearDownReceiver();
        }
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Helper method to forcefully set the worker's cached state,
     * completely bypassing the need for complex static/final Mockito extensions.
     */
    private void setWorkerCacheState(String dmaPackage, boolean isSupported) throws Exception {
        Field packageField = MessageUpgradeWorker.class.getDeclaredField(
                "mCachedDefaultSmsPackage");
        packageField.setAccessible(true);
        packageField.set(mWorker, dmaPackage);

        Field supportedField = MessageUpgradeWorker.class.getDeclaredField(
                "mCachedIsUpgradeSupported");
        supportedField.setAccessible(true);
        supportedField.set(mWorker, isSupported);
    }

    /**
     * Helper method to verify that a specific PendingIntentCache has been cleared from memory.
     */
    private void assertCacheIsEmpty(String cacheFieldName) throws Exception {
        Field cacheField = MessageUpgradeWorker.class.getDeclaredField(cacheFieldName);
        cacheField.setAccessible(true);

        java.util.Map<?, ?> cache = (java.util.Map<?, ?>) cacheField.get(mWorker);

        assertTrue("Memory leak: " + cacheFieldName + " should be empty after dispatch!",
                cache.isEmpty());
    }

    /**
     * Helper method to replace the worker's service wrapper with our mocked instance.
     */
    private void injectMockServiceWrapper(MessageUpgradeWorker worker,
            AlternativeMessageTransportServiceWrapper mockWrapper) throws Exception {
        Field field = MessageUpgradeWorker.class.getDeclaredField("mServiceWrapper");
        field.setAccessible(true);
        field.set(worker, mockWrapper);
    }

    private void setUpReceiver() {
        mContext.registerReceiver(mTestReceiver, new IntentFilter(ACTION_SENT),
                Context.RECEIVER_EXPORTED);
        mLatch = new CountDownLatch(1);
    }

    private void tearDownReceiver() {
        mContext.unregisterReceiver(mTestReceiver);
    }

    private List<PendingIntent> createPendingIntentSingletonList() {
        Intent intent = new Intent(ACTION_SENT);
        PendingIntent sentIntent = PendingIntent.getBroadcast(
                mContext, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return Collections.singletonList(sentIntent);
    }
}
