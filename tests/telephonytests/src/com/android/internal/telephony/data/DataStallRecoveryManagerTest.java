/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.internal.telephony.data;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.Annotation.ValidationStatus;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentResolver;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.data.DataNetworkController.DataNetworkControllerCallback;
import com.android.internal.telephony.data.DataSettingsManager.DataSettingsManagerCallback;
import com.android.internal.telephony.data.DataStallRecoveryManager.DataStallRecoveryManagerCallback;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class DataStallRecoveryManagerTest extends TelephonyTest {
    private static final String KEY_IS_DSRS_DIAGNOSTICS_ENABLED =
            "is_dsrs_diagnostics_enabled";
    private FakeContentResolver mFakeContentResolver;

    // Mocked classes
    private DataStallRecoveryManagerCallback mDataStallRecoveryManagerCallback;

    private DataStallRecoveryManager mDataStallRecoveryManager;

    private TelephonyCallback.ActiveDataSubscriptionIdListener mActiveSubIdListener;

    private static final int FAKE_SUB_ID = 42;

    private long mTestRandomOffsets = 0L;

    /**
     * The fake content resolver used to receive change event from global settings
     * and notify observer of a change in content in DataStallRecoveryManager
     */
    private class FakeContentResolver extends MockContentResolver {
        @Override
        public void notifyChange(Uri uri, ContentObserver observer) {
            super.notifyChange(uri, observer);
            logd("onChanged(uri=" + uri + ")" + observer);
            if (observer != null) {
                observer.dispatchChange(false, uri);
            } else {
                mDataStallRecoveryManager.getContentObserver().dispatchChange(false, uri);
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        logd("DataStallRecoveryManagerTest +Setup!");
        super.setUp(getClass().getSimpleName());
        Field field = DataStallRecoveryManager.class.getDeclaredField("mPredictWaitingMillis");
        field.setAccessible(true);

        // Mock TelecomManager
        when(mContext.getSystemService(Context.TELECOM_SERVICE)).thenReturn(mTelecomManager);

        mFakeContentResolver = new FakeContentResolver();
        doReturn(mFakeContentResolver).when(mContext).getContentResolver();
        // Set the global settings for action enabled state and duration to
        // the default test values.
        Settings.Global.putString(mFakeContentResolver, Settings.Global.DSRM_DURATION_MILLIS,
                "100,100,100,100,0");
        Settings.Global.putString(mFakeContentResolver, Settings.Global.DSRM_ENABLED_ACTIONS,
                "true,true,false,true,true");

        mDataStallRecoveryManagerCallback = mock(DataStallRecoveryManagerCallback.class);
        mCarrierConfigManager = mPhone.getContext().getSystemService(CarrierConfigManager.class);
        long[] dataStallRecoveryTimersArray = new long[] {100, 100, 100, 100};
        boolean[] dataStallRecoveryStepsArray = new boolean[] {false, false, true, false, false};
        long[] dataStallRecoveryRandomizationArray = new long[] {0, 0, 0, 0};

        doReturn(dataStallRecoveryTimersArray)
                .when(mDataConfigManager)
                .getDataStallRecoveryDelayMillis();
        doReturn(dataStallRecoveryStepsArray)
                .when(mDataConfigManager)
                .getDataStallRecoveryShouldSkipArray();
        doReturn(dataStallRecoveryRandomizationArray)
                .when(mDataConfigManager)
                .getDataStallRecoveryRandomizationMillis();
        doReturn(true).when(mDataNetworkController).isInternetDataAllowed(true);
        doReturn(FAKE_SUB_ID).when(mPhone).getSubId();

        doAnswer(invocation -> {
            ((Runnable) invocation.getArguments()[0]).run();
            return null;
        }).when(mDataStallRecoveryManagerCallback).invokeFromExecutor(any(Runnable.class));

        mTelephonyManager = mock(TelephonyManager.class);
        ArgumentCaptor<TelephonyCallback> telephonyCallbackCaptor =
                ArgumentCaptor.forClass(TelephonyCallback.class);
        mContextFixture.setSystemService(Context.TELEPHONY_SERVICE, mTelephonyManager);

        mDataStallRecoveryManager =
                new DataStallRecoveryManager(
                        mPhone,
                        mDataNetworkController,
                        mMockedWwanDataServiceManager,
                        mFeatureFlags,
                        mTestableLooper.getLooper(),
                        mDataStallRecoveryManagerCallback);
        verify(mTelephonyManager).registerTelephonyCallback(
                any(),
                telephonyCallbackCaptor.capture());
        mActiveSubIdListener =
                (TelephonyCallback.ActiveDataSubscriptionIdListener)
                telephonyCallbackCaptor.getValue();
        mActiveSubIdListener.onActiveDataSubscriptionIdChanged(FAKE_SUB_ID);
        mTestableLooper.processAllMessages();

        verify(mTelephonyManager).registerTelephonyCallback(
                any(),
                telephonyCallbackCaptor.capture());
        TelephonyCallback.ActiveDataSubscriptionIdListener activeSubIdListener =
                (TelephonyCallback.ActiveDataSubscriptionIdListener)
                telephonyCallbackCaptor.getValue();
        activeSubIdListener.onActiveDataSubscriptionIdChanged(FAKE_SUB_ID);
        mTestableLooper.processAllMessages();

        field.set(mDataStallRecoveryManager, 0L);

        doReturn(false).when(mTelecomManager).isInEmergencyCall();
        doReturn(false).when(mPhone).isInEcm();


        logd("DataStallRecoveryManagerTest -Setup!");
    }

    @After
    public void tearDown() throws Exception {
        mFakeContentResolver = null;
        mDataStallRecoveryManager = null;
        super.tearDown();
    }

    private void sendValidationStatusCallback(@ValidationStatus int status) {
        ArgumentCaptor<DataNetworkControllerCallback> dataNetworkControllerCallbackCaptor =
                ArgumentCaptor.forClass(DataNetworkControllerCallback.class);
        verify(mDataNetworkController, times(2))
                .registerDataNetworkControllerCallback(
                        dataNetworkControllerCallbackCaptor.capture());
        DataNetworkControllerCallback dataNetworkControllerCallback =
                dataNetworkControllerCallbackCaptor.getAllValues().get(0);
        dataNetworkControllerCallback.onInternetDataNetworkValidationStatusChanged(status);
    }

    private void sendDataEabledCallback(boolean isEnabled) {
        ArgumentCaptor<DataSettingsManagerCallback> dataSettingsManagerCallbackCaptor =
                ArgumentCaptor.forClass(DataSettingsManagerCallback.class);
        verify(mDataSettingsManager).registerCallback(dataSettingsManagerCallbackCaptor.capture());

        // Data enabled
        doReturn(isEnabled).when(mDataSettingsManager).isDataEnabled();
        dataSettingsManagerCallbackCaptor.getValue().onDataEnabledChanged(isEnabled,
                TelephonyManager.DATA_ENABLED_REASON_USER, "");
    }

    private void sendOnInternetDataNetworkCallback(boolean isConnected) {
        ArgumentCaptor<DataNetworkControllerCallback> dataNetworkControllerCallbackCaptor =
                ArgumentCaptor.forClass(DataNetworkControllerCallback.class);
        verify(mDataNetworkController, times(2))
                .registerDataNetworkControllerCallback(
                        dataNetworkControllerCallbackCaptor.capture());
        DataNetworkControllerCallback dataNetworkControllerCallback =
                dataNetworkControllerCallbackCaptor.getAllValues().get(0);

        DataNetwork network = mock(DataNetwork.class);
        NetworkCapabilities netCaps = new NetworkCapabilities();
        doReturn(netCaps).when(network).getNetworkCapabilities();
        if (!isConnected) {
            // A network that doesn't need to be tracked for validation
            netCaps.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        }
        dataNetworkControllerCallback.onConnectedInternetDataNetworksChanged(Set.of(network));
        processAllMessages();
    }

    @Test
    public void testRecoveryStepPDPReset() throws Exception {
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        sendOnInternetDataNetworkCallback(true);
        mDataStallRecoveryManager.setRecoveryAction(1);
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();

        logd("Sending validation failed callback");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllFutureMessages();

        verify(mDataStallRecoveryManagerCallback).onDataStallReestablishInternet();
    }

    @Test
    public void testRecoveryStepRestartRadio() throws Exception {
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        sendOnInternetDataNetworkCallback(true);
        mDataStallRecoveryManager.setRecoveryAction(3);
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();

        logd("Sending validation failed callback");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllFutureMessages();

        verify(mSST, times(1)).powerOffRadioSafely();
    }

    @Test
    public void testRecoveryStepModemReset() throws Exception {
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        sendOnInternetDataNetworkCallback(true);
        mDataStallRecoveryManager.setRecoveryAction(4);
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();

        logd("Sending validation failed callback");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);

        processAllFutureMessages();

        verify(mPhone, times(1)).rebootModem(any());
    }

    @Test
    public void testDoNotDoRecoveryActionWhenPoorSignal() throws Exception {
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        sendOnInternetDataNetworkCallback(true);
        mDataStallRecoveryManager.setRecoveryAction(3);
        doReturn(1).when(mSignalStrength).getLevel();
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();

        logd("Sending validation failed callback");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);

        processAllFutureMessages();

        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(3);
    }

    @Test
    public void testDoNotDoRecoveryActionWhenDialCall() throws Exception {
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        sendOnInternetDataNetworkCallback(true);
        mDataStallRecoveryManager.setRecoveryAction(3);
        doReturn(3).when(mSignalStrength).getLevel();
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(PhoneConstants.State.OFFHOOK).when(mPhone).getState();

        logd("Sending validation failed callback");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);

        processAllFutureMessages();

        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(3);
    }

    @Test
    public void testDoNotDoRecoveryBySendMessageDelayedWhenDialCall() throws Exception {
        sendOnInternetDataNetworkCallback(true);
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        mDataStallRecoveryManager.setRecoveryAction(0);
        doReturn(PhoneConstants.State.OFFHOOK).when(mPhone).getState();
        doReturn(3).when(mSignalStrength).getLevel();
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        logd("Sending validation failed callback");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllMessages();
        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(1);
        mDataStallRecoveryManager.sendMessageDelayed(
                mDataStallRecoveryManager.obtainMessage(3), 1000);
        moveTimeForward(15000);
        processAllMessages();

        // should not change the recovery action due to there is an active call.
        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(1);
    }

    @Test
    public void testDoNotContinueRecoveryActionAfterModemReset() throws Exception {
        sendOnInternetDataNetworkCallback(true);
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        mDataStallRecoveryManager.setRecoveryAction(0);
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();
        doReturn(3).when(mSignalStrength).getLevel();
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        logd("Sending validation failed callback");

        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(0);
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllMessages();
        moveTimeForward(101);
        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(1);

        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllMessages();
        moveTimeForward(101);
        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(3);

        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllMessages();
        moveTimeForward(101);
        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(4);

        // Handle multiple VALIDATION_STATUS_NOT_VALID and make sure we don't attempt recovery
        for (int i = 0; i < 4; i++) {
            sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
            logd("Sending validation failed callback");
            processAllMessages();
            moveTimeForward(101);
            assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(0);
        }
    }

    @Test
    public void testDoRecoveryWhenMeetDataStallAgain() throws Exception {
        sendOnInternetDataNetworkCallback(true);
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        mDataStallRecoveryManager.setRecoveryAction(0);
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();
        doReturn(3).when(mSignalStrength).getLevel();
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        logd("Sending validation failed callback");

        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(0);
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllMessages();
        moveTimeForward(101);
        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(1);

        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllMessages();
        moveTimeForward(101);
        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(3);

        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllMessages();
        moveTimeForward(101);
        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(4);

        // Handle multiple VALIDATION_STATUS_NOT_VALID and make sure we don't attempt recovery
        for (int i = 0; i < 4; i++) {
            sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
            logd("Sending validation failed callback");
            processAllMessages();
            moveTimeForward(101);
            assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(0);
        }

        moveTimeForward(101);
        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(0);

        mDataStallRecoveryManager.sendMessageDelayed(
                mDataStallRecoveryManager.obtainMessage(0), 1000);
        processAllMessages();
        processAllMessages();
        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(0);
    }

    @Test
    public void testDoNotDoRecoveryWhenDataNoService() throws Exception {
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        sendOnInternetDataNetworkCallback(true);
        mDataStallRecoveryManager.setRecoveryAction(1);
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();
        doReturn(false).when(mDataNetworkController).isInternetDataAllowed(true);

        logd("Sending validation failed callback");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllFutureMessages();

        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(1);
    }

    @Test
    public void testDoNotDoRecoveryWhenDataNetworkNotConnected() throws Exception {
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        sendOnInternetDataNetworkCallback(true);
        mDataStallRecoveryManager.setRecoveryAction(1);
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();
        sendOnInternetDataNetworkCallback(false);

        logd("Sending validation failed callback");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllFutureMessages();

        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(1);
    }

    @Test
    public void testDoNotDoRecoveryIfNoValidationPassedYet() throws Exception {
        sendOnInternetDataNetworkCallback(false);
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();

        logd("Sending validation failed callback");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllFutureMessages();

        // Handle multiple VALIDATION_STATUS_NOT_VALID and make sure we don't attempt recovery
        for (int i = 0; i < 4; i++) {
            sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
            logd("Sending validation failed callback");
            processAllMessages();
            moveTimeForward(101);
            assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(0);
        }
    }

    @Test
    public void testStartTimeNotZero() throws Exception {
        sendOnInternetDataNetworkCallback(false);
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();

        logd("Sending validation failed callback");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllFutureMessages();

        for (int i = 0; i < 2; i++) {
            sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
            logd("Sending validation failed callback");
            processAllMessages();
            moveTimeForward(101);
        }
        assertThat(mDataStallRecoveryManager.mDataStallStartMs != 0).isTrue();
    }

    /**
     * Tests the DSRM process to send three intents for three action changes.
     */
    @Test
    public void testInitialValidStateIsNotDataStall() throws Exception {
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        verify(mPhone.getContext(), never()).sendBroadcast(any());
    }

    /**
     * Tests update action enable state and duration from global settings.
     */
    @Test
    public void testUpdateGlobalSettings() throws Exception {
        Field field = DataStallRecoveryManager.class.getDeclaredField("mPredictWaitingMillis");
        field.setAccessible(true);

        // Set duration to 10000/20000/30000/40000
        Settings.Global.putString(
                mFakeContentResolver, Settings.Global.DSRM_DURATION_MILLIS,
                "10000,20000,30000,40000,0");
        // Send onChange event with Settings.Global.DSRM_DURATION_MILLIS to fake ContentResolver
        mFakeContentResolver.notifyChange(
                Settings.Global.getUriFor(Settings.Global.DSRM_DURATION_MILLIS), null);
        processAllFutureMessages();
        // Verify that the durations are correct values.
        assertThat(mDataStallRecoveryManager.getDataStallRecoveryDelayMillis(0)).isEqualTo(10000L);
        assertThat(mDataStallRecoveryManager.getDataStallRecoveryDelayMillis(1)).isEqualTo(20000L);
        assertThat(mDataStallRecoveryManager.getDataStallRecoveryDelayMillis(2)).isEqualTo(30000L);
        assertThat(mDataStallRecoveryManager.getDataStallRecoveryDelayMillis(3)).isEqualTo(40000L);

        // Set action enable state to true/false/false/false/true
        Settings.Global.putString(
                mFakeContentResolver, Settings.Global.DSRM_ENABLED_ACTIONS,
                "true,false,false,false,true");
        // Send onChange event with Settings.Global.DSRM_ENABLED_ACTIONS to fake ContentResolver
        mFakeContentResolver.notifyChange(
                Settings.Global.getUriFor(Settings.Global.DSRM_ENABLED_ACTIONS), null);
        processAllFutureMessages();
        // Verify that the action enable state are correct values.
        assertThat(mDataStallRecoveryManager.shouldSkipRecoveryAction(0)).isEqualTo(false);
        assertThat(mDataStallRecoveryManager.shouldSkipRecoveryAction(1)).isEqualTo(true);
        assertThat(mDataStallRecoveryManager.shouldSkipRecoveryAction(2)).isEqualTo(true);
        assertThat(mDataStallRecoveryManager.shouldSkipRecoveryAction(3)).isEqualTo(true);
        assertThat(mDataStallRecoveryManager.shouldSkipRecoveryAction(4)).isEqualTo(false);
        // Check the predict waiting millis
        assertThat(field.get(mDataStallRecoveryManager)).isEqualTo(1000L);
        // Test predict waiting millis to rollback to 0 if there is no global duration and action
        // Set duration to empty
        Settings.Global.putString(
                mFakeContentResolver, Settings.Global.DSRM_DURATION_MILLIS,
                "");
        // Send onChange event with Settings.Global.DSRM_DURATION_MILLIS to fake ContentResolver
        mFakeContentResolver.notifyChange(
                Settings.Global.getUriFor(Settings.Global.DSRM_DURATION_MILLIS), null);
        processAllFutureMessages();
        // Set action to empty
        Settings.Global.putString(
                mFakeContentResolver, Settings.Global.DSRM_ENABLED_ACTIONS,
                "");
        // Send onChange event with Settings.Global.DSRM_ENABLED_ACTIONS to fake ContentResolver
        mFakeContentResolver.notifyChange(
                Settings.Global.getUriFor(Settings.Global.DSRM_ENABLED_ACTIONS), null);
        processAllFutureMessages();
        // Check if predict waiting millis is 0
        assertThat(field.get(mDataStallRecoveryManager)).isEqualTo(0L);
    }

    @Test
    public void testRecoveryActionAfterDataEnabled() throws Exception {
        sendDataEabledCallback(true);
        sendOnInternetDataNetworkCallback(true);
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        mDataStallRecoveryManager.setRecoveryAction(0);
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();
        doReturn(3).when(mSignalStrength).getLevel();
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        logd("Sending validation failed callback");

        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(0);
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllMessages();
        moveTimeForward(101);
        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(1);

        // test mobile data off/on
        sendDataEabledCallback(false);
        sendDataEabledCallback(true);

        // recovery action will jump to next action if user doing the mobile data off/on.
        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(3);
    }

    @Test
    public void testJumpToRecoveryActionRadioRestart() throws Exception {
        sendDataEabledCallback(true);
        sendOnInternetDataNetworkCallback(true);
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        mDataStallRecoveryManager.setRecoveryAction(0);

        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();
        doReturn(3).when(mSignalStrength).getLevel();
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(TelephonyManager.RADIO_POWER_ON).when(mPhone).getRadioPowerState();
        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(0);

        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        moveTimeForward(200);
        processAllMessages();
        moveTimeForward(200);
        mDataStallRecoveryManager.sendMessageDelayed(
                mDataStallRecoveryManager.obtainMessage(3), 1000);
        processAllMessages();
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        moveTimeForward(200);
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllMessages();
        moveTimeForward(200);

        // recovery action will jump to modem reset action if user doing the radio restart.
        assertThat(mDataStallRecoveryManager.getRecoveryAction()).isEqualTo(4);
    }

    @Test
    public void testDoNotDoRecoveryActionWhenActiveCall() throws Exception {
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        sendOnInternetDataNetworkCallback(true);
        mDataStallRecoveryManager.setRecoveryAction(
                DataStallRecoveryManager.RECOVERY_ACTION_RADIO_RESTART);
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        // Simulate active call
        doReturn(PhoneConstants.State.OFFHOOK).when(mPhone).getState();

        logd("Sending validation failed callback");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllFutureMessages();

        verify(mSST, never()).powerOffRadioSafely();
        verify(mPhone, never()).rebootModem(any());

        assertThat(mDataStallRecoveryManager.getRecoveryAction())
                .isEqualTo(DataStallRecoveryManager.RECOVERY_ACTION_RADIO_RESTART);
    }

    // set private boolean field using reflection
    private void setPrivateBooleanField(Object obj, String fieldName, boolean value)
            throws Exception {
        Field field = DataStallRecoveryManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(obj, value);
    }

    // get private boolean field using reflection
    private boolean getPrivateBooleanField(Object obj, String fieldName) throws Exception {
        Field field = DataStallRecoveryManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(obj);
    }

    /**
     * Test that setRecoveryAction is skipped if the network is invalid and recovery has not yet
     * started.
     */
    @Test
    public void testSetRecoveryAction_skipWhenInvalidNetworkAndNotStarted() throws Exception {
        // Ensure initial state has recovery not started
        assertThat(mDataStallRecoveryManager.getRecoveryAction())
                .isEqualTo(DataStallRecoveryManager.RECOVERY_ACTION_GET_DATA_CALL_LIST);
        assertThat(getPrivateBooleanField(mDataStallRecoveryManager, "mRecoveryTriggered"))
                .isFalse();

        // set network state to invalid
        setPrivateBooleanField(mDataStallRecoveryManager, "mIsValidNetwork", false);

        mDataStallRecoveryManager.setRecoveryAction(
                DataStallRecoveryManager.RECOVERY_ACTION_CLEANUP);
        processAllMessages();

        // Verify that the recovery action was NOT changed.
        assertThat(mDataStallRecoveryManager.getRecoveryAction())
                .isEqualTo(DataStallRecoveryManager.RECOVERY_ACTION_GET_DATA_CALL_LIST);
    }

    /** Test that the DSRM state is reset when the SIM state changes to ABSENT. */
    @Test
    public void testOnSimStateChanged_absentResetsState() throws Exception {
        ArgumentCaptor<DataNetworkControllerCallback> dataNetworkControllerCallbackCaptor =
                ArgumentCaptor.forClass(DataNetworkControllerCallback.class);
        verify(mDataNetworkController, times(2))
                .registerDataNetworkControllerCallback(
                        dataNetworkControllerCallbackCaptor.capture());
        DataNetworkControllerCallback callback =
                dataNetworkControllerCallbackCaptor.getAllValues().get(0);
        assertNotNull(callback);

        // Set network to valid initially
        setPrivateBooleanField(mDataStallRecoveryManager, "mIsValidNetwork", true);
        mDataStallRecoveryManager.setRecoveryAction(
                DataStallRecoveryManager.RECOVERY_ACTION_CLEANUP);
        setPrivateBooleanField(mDataStallRecoveryManager, "mRecoveryTriggered", true);
        setPrivateBooleanField(mDataStallRecoveryManager, "mDataStalled", true);
        assertThat(mDataStallRecoveryManager.getRecoveryAction())
                .isEqualTo(DataStallRecoveryManager.RECOVERY_ACTION_CLEANUP);
        assertThat(getPrivateBooleanField(mDataStallRecoveryManager, "mIsValidNetwork")).isTrue();

        // Trigger the onSimStateChanged callback with SIM_STATE_ABSENT
        logd("Simulating SIM_STATE_ABSENT");
        callback.onSimStateChanged(TelephonyManager.SIM_STATE_ABSENT);
        processAllMessages(); // Process messages potentially posted by reset()

        assertThat(getPrivateBooleanField(mDataStallRecoveryManager, "mIsValidNetwork")).isFalse();
    }

    @Test
    public void testDoNotDoRecoveryActionWhenInEmergencyCall() throws Exception {
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        sendOnInternetDataNetworkCallback(true);
        mDataStallRecoveryManager.setRecoveryAction(
                DataStallRecoveryManager.RECOVERY_ACTION_CLEANUP);
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(3).when(mSignalStrength).getLevel();
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();
        doReturn(true).when(mDataNetworkController).isInternetDataAllowed(true);
        // set not in ECM
        doReturn(false).when(mPhone).isInEcm();
        // set in emergency call
        doReturn(true).when(mTelecomManager).isInEmergencyCall();
        logd("Sending validation failed callback while in emergency call");
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllFutureMessages();

        verify(mDataStallRecoveryManagerCallback, never()).onDataStallReestablishInternet();
        verify(mSST, never()).powerOffRadioSafely();
        verify(mPhone, never()).rebootModem(any());

        // Still at cleanup
        assertThat(mDataStallRecoveryManager.getRecoveryAction())
                .isEqualTo(DataStallRecoveryManager.RECOVERY_ACTION_CLEANUP);
    }
    @Test
    public void testDoNotDoRecoveryActionWhenInEcm() throws Exception {
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        sendOnInternetDataNetworkCallback(true);
        mDataStallRecoveryManager.setRecoveryAction(
                DataStallRecoveryManager.RECOVERY_ACTION_CLEANUP);
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(3).when(mSignalStrength).getLevel();
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();
        doReturn(true).when(mDataNetworkController).isInternetDataAllowed(true);
        // set in ECM
        doReturn(true).when(mPhone).isInEcm();
        // set not in emergency call
        doReturn(false).when(mTelecomManager).isInEmergencyCall();
        logd("Sending validation failed callback while in ECM");

        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllFutureMessages();

        verify(mDataStallRecoveryManagerCallback, never()).onDataStallReestablishInternet();
        verify(mSST, never()).powerOffRadioSafely();
        verify(mPhone, never()).rebootModem(any());

        // Still at cleanup
        assertThat(mDataStallRecoveryManager.getRecoveryAction())
                .isEqualTo(DataStallRecoveryManager.RECOVERY_ACTION_CLEANUP);
    }

    /**
     * Test that doRecovery is skipped if isRecoveryNeeded fails, which can happen if conditions
     * change between the check and the execution of the recovery action.
     */
    @Test
    public void testDoRecovery_skippedWhenRecoveryNotNeeded() throws Exception {
        // Set phone to be in a call
        doReturn(PhoneConstants.State.OFFHOOK).when(mPhone).getState();
        mDataStallRecoveryManager.setRecoveryAction(
                DataStallRecoveryManager.RECOVERY_ACTION_RADIO_RESTART);
        setPrivateBooleanField(mDataStallRecoveryManager, "mRecoveryTriggered", true);

        // Send the DO_RECOVERY event to bypass the initial checks.
        mDataStallRecoveryManager.sendEmptyMessage(2 /* EVENT_DO_RECOVERY */);
        processAllMessages();

        // Verify the recovery action was NOT performed because isRecoveryNeeded() fail.
        verify(mSST, never()).powerOffRadioSafely();
        // Verify that the check timer was rescheduled (EVENT_SEND_DATA_STALL_BROADCAST)
        assertThat(mDataStallRecoveryManager.hasMessages(1)).isTrue();
    }

    /**
     * Test that if validation fails while a recovery is already in progress (triggered),
     * a new recovery sequence is not started.
     */
    @Test
    public void testOnInternetValidationStatusChanged_skipWhenRecoveryTriggered() throws Exception {
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        sendOnInternetDataNetworkCallback(true);
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(PhoneConstants.State.IDLE).when(mPhone).getState();

        // Manually set recovery as triggered.
        setPrivateBooleanField(mDataStallRecoveryManager, "mRecoveryTriggered", true);

        // Sending a validation failure.
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllMessages();

        // Verify that a new recovery was not triggered (EVENT_SEND_DATA_STALL_BROADCAST)
        assertThat(mDataStallRecoveryManager.hasMessages(1)).isFalse();
    }

    @Test
    public void testInactiveNetworkIsNotADataStall() {
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_VALID);
        sendOnInternetDataNetworkCallback(true);
        processAllMessages();
        // Verify here and below to ensure that the broadcast is not coming from the later
        // transition.
        verify(mPhone.getContext(), never()).sendBroadcast(any());

        mActiveSubIdListener.onActiveDataSubscriptionIdChanged(21);
        sendValidationStatusCallback(NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        processAllMessages();

        verify(mPhone.getContext(), never()).sendBroadcast(any());
    }

    @Test
    public void testRandomizedDelayInitialization() throws Exception {
        // Enable randomization and provide config data.
        doReturn(true).when(mFeatureFlags).enableDataStallRecoveryRandomization();

        long[] baseDelays = new long[] {1000, 2000, 3000, 4000};
        doReturn(baseDelays).when(mDataConfigManager).getDataStallRecoveryDelayMillis();

        long[] maxRandomOffsets = new long[4];
        // Set max randomization for all indices
        Arrays.fill(maxRandomOffsets, 100L);
        doReturn(maxRandomOffsets)
                .when(mDataConfigManager)
                .getDataStallRecoveryRandomizationMillis();

        mTestRandomOffsets = 10L;

        mDataStallRecoveryManager =
                new DataStallRecoveryManager(
                        mPhone,
                        mDataNetworkController,
                        mMockedWwanDataServiceManager,
                        mFeatureFlags,
                        mTestableLooper.getLooper(),
                        mDataStallRecoveryManagerCallback) {
                    @Override
                    protected long getRandomOffsetsMillis(long bound) {
                        assertThat(bound).isEqualTo(100L);
                        return mTestRandomOffsets;
                    }
                };
        mTestableLooper.processAllMessages(); // Process initial messages from constructor

        // Verify the randomized delays using getDataStallRecoveryDelayMillis.
        assertThat(mDataStallRecoveryManager.getDataStallRecoveryDelayMillis(0))
                .isEqualTo(baseDelays[0] + mTestRandomOffsets); // 1000 + 10
        assertThat(mDataStallRecoveryManager.getDataStallRecoveryDelayMillis(1))
                .isEqualTo(baseDelays[1] + mTestRandomOffsets); // 2000 + 10
        assertThat(mDataStallRecoveryManager.getDataStallRecoveryDelayMillis(2))
                .isEqualTo(baseDelays[2] + mTestRandomOffsets); // 3000 + 10
        assertThat(mDataStallRecoveryManager.getDataStallRecoveryDelayMillis(3))
                .isEqualTo(baseDelays[3] + mTestRandomOffsets); // 4000 + 10
    }

    //TODO: b/479295438, Ceate the test case for Random timer for each recovery actions.
}
