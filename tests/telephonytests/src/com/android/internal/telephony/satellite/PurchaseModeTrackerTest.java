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

import static android.telephony.TelephonyManager.SATELLITE_PURCHASE_MODE_STATE_ACTIVE;
import static android.telephony.TelephonyManager.SATELLITE_PURCHASE_MODE_STATE_INACTIVE;
import static android.telephony.TelephonyManager.SATELLITE_PURCHASE_MODE_STATE_NETWORK_SETUP;
import static android.telephony.TelephonyManager.SATELLITE_PURCHASE_MODE_STATE_NETWORK_TEARDOWN;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Pair;

import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@RequiresFlagsEnabled(Flags.FLAG_SATELLITE_UPSELL_26Q4)
public class PurchaseModeTrackerTest extends TelephonyTest {
    private static final String TAG = "PurchaseModeTrackerTest";

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final int SUB_ID = 0;

    private static final Pair<Boolean, Integer> NETWORK_SETUP_STATE =
            new Pair<>(true, SATELLITE_PURCHASE_MODE_STATE_NETWORK_SETUP);

    private static final Pair<Boolean, Integer> ACTIVE_STATE =
            new Pair<>(true, SATELLITE_PURCHASE_MODE_STATE_ACTIVE);

    private static final Pair<Boolean, Integer> NETWORK_TEARDOWN_STATE =
            new Pair<>(false, SATELLITE_PURCHASE_MODE_STATE_NETWORK_TEARDOWN);

    private static final Pair<Boolean, Integer> INACTIVE_STATE =
            new Pair<>(false, SATELLITE_PURCHASE_MODE_STATE_INACTIVE);

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        MockitoAnnotations.initMocks(this);
        logd(TAG + " Setup!");

        doReturn(SUB_ID).when(mPhone).getSubId();
        when(mPhone.getPhoneId()).thenReturn(0);
    }

    @After
    public void tearDown() throws Exception {
        logd(TAG + " tearDown");
        super.tearDown();
    }


    @Test
    public void testNotifySatellitePurchaseModeFlow() {
        doReturn(true).when(mFeatureFlags).satelliteUpsell26q4();

        PurchaseModeTracker purchaseModeTracker = new PurchaseModeTracker();

        Pair<Boolean, Integer> testPair = NETWORK_SETUP_STATE;
        purchaseModeTracker.setSatellitePurchaseModeInProgress(
                SUB_ID, testPair.first, testPair.second);
        purchaseModeTracker.notifySatellitePurchaseModeInProgress(mPhone);
        processAllMessages();
        verify(mPhone, times(1))
                .notifySatellitePurchaseModeChanged(eq(testPair.first), eq(testPair.second));
        clearInvocations(mPhone);

        testPair = ACTIVE_STATE;
        purchaseModeTracker.setSatellitePurchaseModeInProgress(
                SUB_ID, testPair.first, testPair.second);
        purchaseModeTracker.notifySatellitePurchaseModeInProgress(mPhone);
        processAllMessages();
        verify(mPhone, times(1))
                .notifySatellitePurchaseModeChanged(eq(testPair.first), eq(testPair.second));
        clearInvocations(mPhone);

        testPair = NETWORK_TEARDOWN_STATE;
        purchaseModeTracker.setSatellitePurchaseModeInProgress(
                SUB_ID, testPair.first, testPair.second);
        purchaseModeTracker.notifySatellitePurchaseModeInProgress(mPhone);
        processAllMessages();
        verify(mPhone, times(1))
                .notifySatellitePurchaseModeChanged(eq(testPair.first), eq(testPair.second));
        clearInvocations(mPhone);

        testPair = INACTIVE_STATE;
        purchaseModeTracker.setSatellitePurchaseModeInProgress(
                SUB_ID, testPair.first, testPair.second);
        purchaseModeTracker.notifySatellitePurchaseModeInProgress(mPhone);
        processAllMessages();
        verify(mPhone, times(1))
                .notifySatellitePurchaseModeChanged(eq(testPair.first), eq(testPair.second));
        clearInvocations(mPhone);
    }
}
