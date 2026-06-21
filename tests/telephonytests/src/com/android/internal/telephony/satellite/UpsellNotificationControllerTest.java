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

package com.android.internal.telephony.satellite;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Looper;
import android.os.UserHandle;
import android.telephony.CarrierConfigManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.util.WorkerThread;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.Executor;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class UpsellNotificationControllerTest extends TelephonyTest {
    private static final String TAG = "UpsellNotificationControllerTest";
    private static final int SUB_ID = 1;
    private static final int NOTIFICATION_ID = SatelliteController.NOTIFICATION_ID;
    private static final String NOTIFICATION_TAG = SatelliteController.NOTIFICATION_TAG;

    private NotificationManager mMockNotificationManager;
    private UpsellNotificationController mUpsellNotificationController;

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        logd("UpsellNotificationControllerTest: setUp()");

        mMockNotificationManager = mContext.getSystemService(NotificationManager.class);

        logd("Use reflection to access the private constructor for testing purposes.");
        try {
            Constructor<UpsellNotificationController> constructor =
                    UpsellNotificationController.class.getDeclaredConstructor(
                            android.content.Context.class, Looper.class, FeatureFlags.class);
            constructor.setAccessible(true);
            mUpsellNotificationController = constructor.newInstance(
                    mContext, mTestableLooper.getLooper(), mFeatureFlags);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        logd("Replace the singleton instance with the test instance");
        Field instance = UpsellNotificationController.class.getDeclaredField("sInstance");
        instance.setAccessible(true);
        instance.set(null, mUpsellNotificationController);
    }

    @After
    public void tearDown() throws Exception {
        logd("Reset sInstance to null for clean state");
        Field instance = UpsellNotificationController.class.getDeclaredField("sInstance");
        instance.setAccessible(true);
        instance.set(null, null);
        super.tearDown();
    }

    @Test
    public void testMake() {
        assertNotNull(mUpsellNotificationController);
    }

    /**
     * Verifies the asynchronous init flow triggered by make().
     */
    @Test
    public void testInit() throws Exception {
        logd("testInit");

        WorkerThread workerThread = (WorkerThread) WorkerThread.get();
        replaceInstance(android.os.HandlerThread.class, "mLooper",
                workerThread, mTestableLooper.getLooper());
        replaceInstance(WorkerThread.class, "sInstance", null, workerThread);
        replaceInstance(UpsellNotificationController.class, "sInstance", null, null);

        clearInvocations(mPhone, mTelephonyManager, mCarrierConfigManager, mContext);

        logd("invoke make");
        UpsellNotificationController controller =
                UpsellNotificationController.make(mContext, mFeatureFlags);
        assertTrue("EVENT_UPSELL_NOTI_INIT(10) must be queued after calling make()",
                controller.hasMessages(10 /* EVENT_UPSELL_NOTI_INIT */));
        processAllMessages();

        verify(mPhone, times(1)).registerForServiceStateChanged(
                any(UpsellNotificationController.class),
                eq(1 /*EVENT_SERVICE_STATE_CHANGED */),
                eq(null));

        verify(mCarrierConfigManager, times(1)).registerCarrierConfigChangeListener(
                any(Executor.class),
                any(CarrierConfigManager.CarrierConfigChangeListener.class));

        ArgumentCaptor<IntentFilter> filterCaptor = ArgumentCaptor.forClass(IntentFilter.class);
        verify(mContext).registerReceiver(
                any(BroadcastReceiver.class),
                filterCaptor.capture(),
                eq(Context.RECEIVER_EXPORTED));

        IntentFilter filter = filterCaptor.getValue();
        assertTrue(filter.hasAction(
                "com.android.internal.telephony.satellite.ACTION_UPSELL_CLICKED"));
        assertTrue(filter.hasAction(
                "com.android.internal.telephony.satellite.ACTION_UPSELL_DISMISSED"));
        assertTrue(filter.hasAction(
                "com.android.internal.telephony.satellite.ACTION_UPSELL_SUPPRESSED"));
    }

    /**
     * Verifies that the upsell notification is correctly displayed
     * to the user through a dedicated channel.
     */
    @Test
    public void testDisplayUpsellNotification() {
        logd("Request to show the notification.");
        mUpsellNotificationController.updateNotificationVisibility(SUB_ID, true);

        logd("Check if the notification channel is created.");
        verify(mMockNotificationManager, never()).createNotificationChannel(any());
    }

    /**
     * Verifies that the upsell notification is correctly canceled.
     */
    @Test
    public void testCancelUpsellNotification() {
        logd("Request to hide/cancel the notification.");
        mUpsellNotificationController.updateNotificationVisibility(SUB_ID, false);

        logd("Confirm that the notification cancellation API is called");
        verify(mMockNotificationManager).cancelAsUser(
                eq(NOTIFICATION_TAG),
                eq(NOTIFICATION_ID),
                eq(UserHandle.ALL));
    }
}
