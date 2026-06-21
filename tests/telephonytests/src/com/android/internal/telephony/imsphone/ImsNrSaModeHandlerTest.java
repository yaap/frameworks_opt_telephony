/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.telephony.imsphone;

import static android.telephony.CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA;
import static android.telephony.CarrierConfigManager.Ims.KEY_NR_SA_DISABLE_POLICY_FOR_EMERGENCY_INT;
import static android.telephony.CarrierConfigManager.Ims.KEY_NR_SA_DISABLE_POLICY_INT;
import static android.telephony.CarrierConfigManager.Ims.NR_SA_DISABLE_POLICY_NONE;
import static android.telephony.CarrierConfigManager.Ims.NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED;
import static android.telephony.CarrierConfigManager.Ims.NR_SA_DISABLE_POLICY_WFC_ESTABLISHED;
import static android.telephony.CarrierConfigManager.Ims.NR_SA_DISABLE_POLICY_WFC_ESTABLISHED_WHEN_VONR_DISABLED;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsWfc.KEY_EMERGENCY_CALL_OVER_EMERGENCY_PDN_BOOL;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE;

import static com.android.internal.telephony.CommandsInterface.IMS_MMTEL_CAPABILITY_VOICE;
import static com.android.internal.telephony.RILConstants.RIL_ERRNO_INVALID_RESPONSE;
import static com.android.internal.telephony.RILConstants.RIL_REQUEST_IS_VONR_ENABLED;
import static com.android.internal.telephony.RILConstants.RIL_REQUEST_SET_N1_MODE_ENABLED;


import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.WorkSource;
import android.telephony.CarrierConfigManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.TelephonyTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public final class ImsNrSaModeHandlerTest extends TelephonyTest {
    @Captor
    ArgumentCaptor<CarrierConfigManager.CarrierConfigChangeListener>
            mCarrierConfigChangeListenerCaptor;
    @Captor
    ArgumentCaptor<Handler> mPreciseCallStateHandlerCaptor;

    private ImsNrSaModeHandler mTestImsNrSaModeHandler;
    private CarrierConfigManager.CarrierConfigChangeListener mCarrierConfigChangeListener;

    @Mock
    private ImsPhoneCall mForegroundCall;
    @Mock
    private ImsPhoneCall mBackgroundCall;
    @Mock
    private ImsPhoneConnection mForegroundConnection;
    @Mock
    private ImsPhoneConnection mBackgroundConnection;


    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        MockitoAnnotations.initMocks(this);

        doReturn(mPhone).when(mImsPhone).getDefaultPhone();
        doReturn(mForegroundCall).when(mImsPhone).getForegroundCall();
        doReturn(mBackgroundCall).when(mImsPhone).getBackgroundCall();
        doReturn(mForegroundConnection).when(mForegroundCall).getFirstConnection();
        doReturn(mBackgroundConnection).when(mBackgroundCall).getFirstConnection();

        // Forward the call from Phone.setN1ModeEnabled(...) to mCi.setN1ModeEnabled(...).
        doAnswer(invocation -> {
            // Retrieve arguments from the setN1ModeEnabled(boolean enable, Message result) method.
            boolean enable = invocation.getArgument(0);
            Message result = invocation.getArgument(1);

            // Invoke the corresponding method on mPhone.mCi (i.e., SimulatedCommands).
            mPhone.mCi.setN1ModeEnabled(enable, result);
            return null;
        }).when(mPhone).setN1ModeEnabled(anyBoolean(), any(Message.class));

        // Forward the call from Phone.isVoNrEnabled(...) to mCi.isVoNrEnabled(...).
        doAnswer(invocation -> {
            // Retrieve arguments from the
            // isVoNrEnabled(Message message, WorkSource workSource) method.
            Message message = invocation.getArgument(0);
            WorkSource workSource = invocation.getArgument(1);

            // Invoke the corresponding method on mPhone.mCi (i.e., SimulatedCommands).
            mPhone.mCi.isVoNrEnabled(message, workSource);
            return null;
        }).when(mPhone).isVoNrEnabled(any(Message.class), nullable(WorkSource.class));

        mTestImsNrSaModeHandler = new ImsNrSaModeHandler(mImsPhone, mTestableLooper.getLooper());

        verify(mCarrierConfigManager).registerCarrierConfigChangeListener(
                any(), mCarrierConfigChangeListenerCaptor.capture());
        mCarrierConfigChangeListener = mCarrierConfigChangeListenerCaptor.getValue();

        doReturn(0).when(mImsPhone).getSubId();
        doReturn(mContextFixture.getCarrierConfigBundle()).when(mCarrierConfigManager)
                .getConfigForSubId(anyInt(), any());
    }

    @After
    public void tearDown() throws Exception {
        mTestImsNrSaModeHandler.tearDown();
        mTestImsNrSaModeHandler = null;
        super.tearDown();
    }

    private void sendCarrierConfigChanged(
            int normalPolicy, int emergencyPolicy, boolean isNrSaSupported,
            boolean isWfcAvailable, boolean isWfcEmergencyOverEpdn) {
        PersistableBundle bundle = mContextFixture.getCarrierConfigBundle();
        bundle.putInt(KEY_NR_SA_DISABLE_POLICY_INT, normalPolicy);
        bundle.putInt(KEY_NR_SA_DISABLE_POLICY_FOR_EMERGENCY_INT, emergencyPolicy);
        if (isNrSaSupported) {
            bundle.putIntArray(KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY,
                    new int[]{CARRIER_NR_AVAILABILITY_SA});
        } else {
            bundle.putIntArray(KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, new int[]{});
        }
        bundle.putBoolean(KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, isWfcAvailable);
        bundle.putBoolean(KEY_EMERGENCY_CALL_OVER_EMERGENCY_PDN_BOOL, isWfcEmergencyOverEpdn);
        mCarrierConfigChangeListener.onCarrierConfigChanged(
                mImsPhone.getPhoneId(), mImsPhone.getSubId(), 0, 0);
    }

    private void setForegroundCallStatus(Call.State state, boolean isEmergency) {
        doReturn(state).when(mForegroundConnection).getState();
        doReturn(isEmergency).when(mForegroundConnection).isEmergencyCall();
    }

    private void setBackgroundCallStatus(Call.State state, boolean isEmergency) {
        doReturn(state).when(mBackgroundConnection).getState();
        doReturn(isEmergency).when(mBackgroundConnection).isEmergencyCall();
    }

    @Test
    public void testTearDown() {
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_WFC_ESTABLISHED,
                NR_SA_DISABLE_POLICY_NONE, true, true, true);
        verify(mImsPhone).registerForPreciseCallStateChanged(
                mPreciseCallStateHandlerCaptor.capture(), anyInt(), any());
        mTestImsNrSaModeHandler.updateImsCapability(IMS_MMTEL_CAPABILITY_VOICE);
        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN);
        setForegroundCallStatus(Call.State.ACTIVE, false);
        mTestImsNrSaModeHandler.onPreciseCallStateChanged();
        processAllMessages();
        assertFalse(mSimulatedCommands.isN1ModeEnabled());

        mTestImsNrSaModeHandler.tearDown();
        processAllMessages();

        verify(mCarrierConfigManager).unregisterCarrierConfigChangeListener(
                mCarrierConfigChangeListener);
        verify(mImsPhone).unregisterForPreciseCallStateChanged(
                mPreciseCallStateHandlerCaptor.getValue());
        assertTrue(mSimulatedCommands.isN1ModeEnabled());
    }

    @Test
    public void testNormalVoWifiRegistered() {
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED,
                NR_SA_DISABLE_POLICY_NONE, true, true, true);
        mTestImsNrSaModeHandler.updateImsCapability(IMS_MMTEL_CAPABILITY_VOICE);
        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN);
        processAllMessages();
        assertFalse(mSimulatedCommands.isN1ModeEnabled());

        mTestImsNrSaModeHandler.onImsUnregistered(REGISTRATION_TECH_IWLAN);
        processAllMessages();
        assertTrue(mSimulatedCommands.isN1ModeEnabled());

        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN);
        processAllMessages();
        assertFalse(mSimulatedCommands.isN1ModeEnabled());

        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_LTE);
        processAllMessages();
        assertTrue(mSimulatedCommands.isN1ModeEnabled());
    }

    @Test
    public void testNormalWfcEstablished() {
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_WFC_ESTABLISHED,
                NR_SA_DISABLE_POLICY_NONE, true, true, true);
        mTestImsNrSaModeHandler.updateImsCapability(IMS_MMTEL_CAPABILITY_VOICE);
        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN);
        processAllMessages();
        assertTrue(mSimulatedCommands.isN1ModeEnabled());

        setForegroundCallStatus(Call.State.ACTIVE, false);
        mTestImsNrSaModeHandler.onPreciseCallStateChanged();
        processAllMessages();
        assertFalse(mSimulatedCommands.isN1ModeEnabled());

        setForegroundCallStatus(Call.State.IDLE, false);
        mTestImsNrSaModeHandler.onPreciseCallStateChanged();
        processAllMessages();
        assertTrue(mSimulatedCommands.isN1ModeEnabled());
    }

    @Test
    public void testNormalWfcEstablishedWhenVonrDisabled() {
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_WFC_ESTABLISHED_WHEN_VONR_DISABLED,
                NR_SA_DISABLE_POLICY_NONE, true, true, true);
        mSimulatedCommands.setVonrEnabled(false);

        mTestImsNrSaModeHandler.updateImsCapability(IMS_MMTEL_CAPABILITY_VOICE);
        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN);
        setForegroundCallStatus(Call.State.ACTIVE, false);
        mTestImsNrSaModeHandler.onPreciseCallStateChanged();
        processAllMessages();
        assertFalse(mSimulatedCommands.isN1ModeEnabled());

        // Call ends, SA should be re-enabled
        setForegroundCallStatus(Call.State.IDLE, false);
        mTestImsNrSaModeHandler.onPreciseCallStateChanged();
        processAllMessages();
        assertTrue(mSimulatedCommands.isN1ModeEnabled());

        mSimulatedCommands.setVonrEnabled(true);
        setForegroundCallStatus(Call.State.ACTIVE, false);
        mTestImsNrSaModeHandler.onPreciseCallStateChanged();
        processAllMessages();
        assertTrue(mSimulatedCommands.isN1ModeEnabled());
    }

    @Test
    public void testNoNrSaSupport() {
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED,
                NR_SA_DISABLE_POLICY_NONE, false, true, true);

        mTestImsNrSaModeHandler.updateImsCapability(IMS_MMTEL_CAPABILITY_VOICE);
        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN);
        processAllMessages();
        assertTrue(mSimulatedCommands.isN1ModeEnabled());
    }

    @Test
    public void testNoVoiceCapability() {
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED,
                NR_SA_DISABLE_POLICY_NONE, true, true, true);

        mTestImsNrSaModeHandler.updateImsCapability(0);
        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN);
        processAllMessages();
        assertTrue(mSimulatedCommands.isN1ModeEnabled());
    }

    @Test
    public void testEmergencyVoWifiRegistered() {
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_NONE,
                NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED, true, true, true);

        mTestImsNrSaModeHandler.onImsEmergencyRegistered(REGISTRATION_TECH_IWLAN);
        processAllMessages();
        assertFalse(mSimulatedCommands.isN1ModeEnabled());

        mTestImsNrSaModeHandler.onImsEmergencyUnregistered(REGISTRATION_TECH_IWLAN);
        processAllMessages();
        assertTrue(mSimulatedCommands.isN1ModeEnabled());

        mTestImsNrSaModeHandler.onImsEmergencyRegistered(REGISTRATION_TECH_IWLAN);
        processAllMessages();
        assertFalse(mSimulatedCommands.isN1ModeEnabled());

        mTestImsNrSaModeHandler.onImsEmergencyRegistered(REGISTRATION_TECH_LTE);
        processAllMessages();
        assertTrue(mSimulatedCommands.isN1ModeEnabled());
    }

    @Test
    public void testEmergencyWfcEstablished() {
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_NONE,
                NR_SA_DISABLE_POLICY_WFC_ESTABLISHED, true, true, true);
        verify(mImsPhone).registerForPreciseCallStateChanged(any(), anyInt(), any());

        mTestImsNrSaModeHandler.onImsEmergencyRegistered(REGISTRATION_TECH_IWLAN);
        processAllMessages();
        assertTrue(mSimulatedCommands.isN1ModeEnabled());

        setForegroundCallStatus(Call.State.ACTIVE, false);
        setBackgroundCallStatus(Call.State.ACTIVE, false);
        mTestImsNrSaModeHandler.onPreciseCallStateChanged();
        processAllMessages();
        assertTrue(mSimulatedCommands.isN1ModeEnabled());

        setForegroundCallStatus(Call.State.ACTIVE, true);
        mTestImsNrSaModeHandler.onPreciseCallStateChanged();
        processAllMessages();
        assertFalse(mSimulatedCommands.isN1ModeEnabled());

        setForegroundCallStatus(Call.State.DISCONNECTED, true);
        mTestImsNrSaModeHandler.onPreciseCallStateChanged();
        processAllMessages();
        assertTrue(mSimulatedCommands.isN1ModeEnabled());
    }

    @Test
    public void testPolicyConflict() {
        // Normal policy wants to enable SA, but emergency policy wants to disable it.
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED,
                NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED, true, true, true);

        mTestImsNrSaModeHandler.updateImsCapability(IMS_MMTEL_CAPABILITY_VOICE);
        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_LTE); // Not on IWLAN
        mTestImsNrSaModeHandler.onImsEmergencyRegistered(REGISTRATION_TECH_IWLAN);
        processAllMessages();

        // Emergency is on IWLAN, so SA should be disabled.
        assertFalse(mSimulatedCommands.isN1ModeEnabled());
    }

    @Test
    public void testPolicyPriority() {
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_WFC_ESTABLISHED_WHEN_VONR_DISABLED,
                NR_SA_DISABLE_POLICY_WFC_ESTABLISHED, true, true, true);

        mSimulatedCommands.setVonrEnabled(true);
        mTestImsNrSaModeHandler.updateImsCapability(IMS_MMTEL_CAPABILITY_VOICE);
        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN);
        mTestImsNrSaModeHandler.onImsEmergencyRegistered(REGISTRATION_TECH_IWLAN);
        setForegroundCallStatus(Call.State.ACTIVE, false);
        setBackgroundCallStatus(Call.State.ACTIVE, true);
        mTestImsNrSaModeHandler.onPreciseCallStateChanged();
        processAllMessages();

        assertFalse(mSimulatedCommands.isN1ModeEnabled());
    }

    @Test
    public void testRegisterUnregisterForPreciseCallStateChanges() {
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED,
                NR_SA_DISABLE_POLICY_NONE, true, true, true);
        verify(mImsPhone, never()).registerForPreciseCallStateChanged(any(), anyInt(), any());
        verify(mImsPhone, times(1)).unregisterForPreciseCallStateChanged(any());

        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_WFC_ESTABLISHED,
                NR_SA_DISABLE_POLICY_NONE, true, true, true);
        verify(mImsPhone, times(1)).registerForPreciseCallStateChanged(
                mPreciseCallStateHandlerCaptor.capture(), anyInt(), any());

        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED,
                NR_SA_DISABLE_POLICY_NONE, true, true, true);
        verify(mImsPhone, times(2)).unregisterForPreciseCallStateChanged(
                mPreciseCallStateHandlerCaptor.getValue());
    }

    @Test
    public void testIsVonrEnabledQueryIsFailed() {
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_WFC_ESTABLISHED_WHEN_VONR_DISABLED,
                NR_SA_DISABLE_POLICY_NONE, true, true, true);
        mSimulatedCommands.setVonrEnabled(false);

        mTestImsNrSaModeHandler.updateImsCapability(IMS_MMTEL_CAPABILITY_VOICE);
        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN);
        setForegroundCallStatus(Call.State.ACTIVE, false);
        // It triggers a failure for the request.
        mSimulatedCommands.setRilRequestErrorCode(
                RIL_REQUEST_IS_VONR_ENABLED, RIL_ERRNO_INVALID_RESPONSE);
        mTestImsNrSaModeHandler.onPreciseCallStateChanged();
        processAllMessages();
        assertTrue(mSimulatedCommands.isN1ModeEnabled());
    }

    @Test
    public void testSetNrSaRequestIsFailed() {
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_NONE,
                NR_SA_DISABLE_POLICY_WFC_ESTABLISHED, true, true, true);
        verify(mImsPhone).registerForPreciseCallStateChanged(any(), anyInt(), any());

        mTestImsNrSaModeHandler.onImsEmergencyRegistered(REGISTRATION_TECH_IWLAN);
        processAllMessages();
        assertTrue(mSimulatedCommands.isN1ModeEnabled());

        setForegroundCallStatus(Call.State.ACTIVE, true);
        // It triggers a failure for the request.
        mSimulatedCommands.setRilRequestErrorCode(
                RIL_REQUEST_SET_N1_MODE_ENABLED, RIL_ERRNO_INVALID_RESPONSE);
        mTestImsNrSaModeHandler.onPreciseCallStateChanged();
        processAllMessages();
        assertTrue(mSimulatedCommands.isN1ModeEnabled());
    }

    @Test
    public void testAsynchronousResponseHandling() {
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_WFC_ESTABLISHED_WHEN_VONR_DISABLED,
                NR_SA_DISABLE_POLICY_NONE, true, true, true);
        mSimulatedCommands.setVonrEnabled(false);

        mTestImsNrSaModeHandler.updateImsCapability(IMS_MMTEL_CAPABILITY_VOICE);
        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN);
        setForegroundCallStatus(Call.State.ACTIVE, false);
        mTestImsNrSaModeHandler.onPreciseCallStateChanged();

        // This part causes a change while waiting for the asynchronous response.
        // Ultimately, it will enable NR SA.
        mTestImsNrSaModeHandler.updateImsCapability(0);
        assertTrue(mSimulatedCommands.isN1ModeEnabled());

        // sends an asynchronous response for isVoNrEnabled query
        mTestableLooper.processMessages(1);
        assertFalse(mTestImsNrSaModeHandler.isNrSaDisabledForWfc());

        // sends an asynchronous response for setN1ModeEnabled request(false)
        mTestableLooper.processMessages(1);
        assertTrue(mTestImsNrSaModeHandler.isNrSaDisabledForWfc());

        // After completing the ongoing operation, it handles the changes
        // that occurred while waiting for the asynchronous response.
        // sends an asynchronous response for setN1ModeEnabled request(true))
        mTestableLooper.processMessages(1);
        assertFalse(mTestImsNrSaModeHandler.isNrSaDisabledForWfc());
    }

    @Test
    public void testWfcNotAvailable() {
        // Send carrier config changed with WFC availability set to false.
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED,
                NR_SA_DISABLE_POLICY_NONE, true, false, true);
        mSimulatedCommands.setVonrEnabled(true);

        // Trigger IMS capability update and IMS registration on WiFi.
        mTestImsNrSaModeHandler.updateImsCapability(IMS_MMTEL_CAPABILITY_VOICE);
        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN);
        processAllMessages();
        // Since WFC is not available, NR SA mode should not be disabled and remain enabled
        // (default).
        assertTrue(mSimulatedCommands.isN1ModeEnabled());
    }

    @Test
    public void testEmergencyOverImsPdnWithNormalPolicyNone() {
        // Set no normal policy but an emergency policy, and emergency call is over IMS PDN (false).
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_NONE,
                NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED, true, true, false);
        mSimulatedCommands.setVonrEnabled(true);

        // Trigger IMS registration on WiFi (shares IMS PDN, so onImsRegistered is called).
        mTestImsNrSaModeHandler.onImsRegistered(REGISTRATION_TECH_IWLAN);
        processAllMessages();
        // Even without a normal policy, NR SA mode should be disabled by the emergency policy.
        assertFalse(mSimulatedCommands.isN1ModeEnabled());

        // Trigger IMS unregistration on WiFi.
        // In a shared PDN environment, onImsUnregistered should also clear the emergency status.
        mTestImsNrSaModeHandler.onImsUnregistered(REGISTRATION_TECH_IWLAN);
        processAllMessages();

        // NR SA mode should be re-enabled.
        assertTrue(mSimulatedCommands.isN1ModeEnabled());
    }

    @Test
    public void testSetNrSaDisablePolicy_CleanupWhenNotSupportedOrUnavailable() {
        // 1. Setup with valid config (NR SA supported, WFC available)
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_WFC_ESTABLISHED,
                NR_SA_DISABLE_POLICY_NONE, true, true, true);

        // Verify registration and capture the handler
        verify(mImsPhone).registerForPreciseCallStateChanged(
                mPreciseCallStateHandlerCaptor.capture(), anyInt(), any());
        Handler handler = mPreciseCallStateHandlerCaptor.getValue();

        // 2. Update config: NR SA NOT supported
        // This triggers the code block: if (!isNrSaSupported || !isWfcAvailable) ...
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_WFC_ESTABLISHED,
                NR_SA_DISABLE_POLICY_NONE, false, true, true);

        // Verify unregistration
        verify(mImsPhone).unregisterForPreciseCallStateChanged(eq(handler));

        // 3. Reset to valid config
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_WFC_ESTABLISHED,
                NR_SA_DISABLE_POLICY_NONE, true, true, true);

        // 4. Update config: WFC NOT available
        // This triggers the code block: if (!isNrSaSupported || !isWfcAvailable) ...
        sendCarrierConfigChanged(NR_SA_DISABLE_POLICY_WFC_ESTABLISHED,
                NR_SA_DISABLE_POLICY_NONE, true, false, true);

        // Verify unregistration again (total 2 times)
        verify(mImsPhone, times(2)).unregisterForPreciseCallStateChanged(eq(handler));
    }
}
