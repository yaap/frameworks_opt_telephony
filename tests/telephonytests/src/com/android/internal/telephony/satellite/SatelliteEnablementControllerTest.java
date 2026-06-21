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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.RequiresFlagsEnabled;
import android.telephony.CarrierConfigManager;
import android.telephony.satellite.EnableRequestAttributes;
import android.telephony.satellite.SatelliteManager;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import com.android.internal.telephony.flags.Flags;

@RequiresFlagsEnabled(Flags.FLAG_SATELLITE_UPSELL_26Q4)
public class SatelliteEnablementControllerTest {
    private static final int SUB_ID = 1;
    @Mock private ManualEnablementController mManualEnablementController;
    @Mock private AutoEnablementController mAutoEnablementController;
    private SatelliteEnablementController mSatelliteEnablementController;

    @Mock private Consumer<Integer> mResultListener;
    private final Executor mExecutor = Runnable::run;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mSatelliteEnablementController = new SatelliteEnablementController(
                mManualEnablementController, mAutoEnablementController);
    }

    @Test
    public void testRequestSatelliteEnabled_enableManual() {
        EnableRequestAttributes attributes = new EnableRequestAttributes.Builder(true)
                .setConnectType(CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_MANUAL)
                .build();
        mSatelliteEnablementController.requestSatelliteEnabled(
                SUB_ID, attributes, mExecutor, mResultListener);
        verify(mManualEnablementController).enableSatellite(attributes, mExecutor, mResultListener);
        verify(mAutoEnablementController, never()).enableSatellite(any(), any(), any());
    }

    @Test
    public void testRequestSatelliteEnabled_disableManual() {
        EnableRequestAttributes attributes = new EnableRequestAttributes.Builder(false)
                .setConnectType(CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_MANUAL)
                .build();
        mSatelliteEnablementController.requestSatelliteEnabled(
                SUB_ID, attributes, mExecutor, mResultListener);
        verify(mManualEnablementController).disableSatellite(
                attributes, mExecutor, mResultListener);
        verify(mAutoEnablementController, never()).disableSatellite(any(), any(), any());
    }

    @Test
    public void testRequestSatelliteEnabled_enableAuto() {
        EnableRequestAttributes attributes = new EnableRequestAttributes.Builder(true)
                .setConnectType(CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC)
                .build();
        mSatelliteEnablementController.requestSatelliteEnabled(
                SUB_ID, attributes, mExecutor, mResultListener);
        verify(mAutoEnablementController).enableSatellite(attributes, mExecutor, mResultListener);
        verify(mManualEnablementController, never()).enableSatellite(any(), any(), any());
    }

    @Test
    public void testRequestSatelliteEnabled_disableAuto() {
        EnableRequestAttributes attributes = new EnableRequestAttributes.Builder(false)
                .setConnectType(CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC)
                .build();
        mSatelliteEnablementController.requestSatelliteEnabled(
                SUB_ID, attributes, mExecutor, mResultListener);
        verify(mAutoEnablementController).disableSatellite(attributes, mExecutor, mResultListener);
        verify(mManualEnablementController, never()).disableSatellite(any(), any(), any());
    }

    @Test
    public void testRequestSatelliteEnabled_invalidConnectType() {
        EnableRequestAttributes attributes = new EnableRequestAttributes.Builder(true)
                .setConnectType(100) // Invalid connect type
                .build();
        mSatelliteEnablementController.requestSatelliteEnabled(
                SUB_ID, attributes, mExecutor, mResultListener);
        verify(mResultListener).accept(SatelliteManager.SATELLITE_RESULT_INVALID_ARGUMENTS);
        verify(mManualEnablementController, never()).enableSatellite(any(), any(), any());
        verify(mAutoEnablementController, never()).enableSatellite(any(), any(), any());
    }

    @Test
    public void testSatelliteEnabled_whenAllDisableReasonsAreCleared() {
        // Disable for POWER reason
        EnableRequestAttributes disablePowerAttributes = new EnableRequestAttributes.Builder(false)
                .setConnectType(CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC)
                .setSatelliteEnablementRequestReason(
                        SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_POWER)
                .build();
        mSatelliteEnablementController.requestSatelliteEnabled(
                SUB_ID, disablePowerAttributes, mExecutor, mResultListener);

        // Disable for USER reason
        EnableRequestAttributes disableUserAttributes = new EnableRequestAttributes.Builder(false)
                .setConnectType(CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC)
                .setSatelliteEnablementRequestReason(
                        SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_USER)
                .build();
        mSatelliteEnablementController.requestSatelliteEnabled(
                SUB_ID, disableUserAttributes, mExecutor, mResultListener);
        assertFalse(mSatelliteEnablementController.isSatelliteEnabled(
                SUB_ID, CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC));

        // Re-enable for USER reason
        EnableRequestAttributes enableUserAttributes = new EnableRequestAttributes.Builder(true)
                .setConnectType(CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC)
                .setSatelliteEnablementRequestReason(
                        SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_USER)
                .build();
        mSatelliteEnablementController.requestSatelliteEnabled(
                SUB_ID, enableUserAttributes, mExecutor, mResultListener);
        // Satellite should still be disabled as POWER reason is still active.
        assertFalse(mSatelliteEnablementController.isSatelliteEnabled(
                SUB_ID, CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC));

        // Re-enable for POWER reason
        EnableRequestAttributes enablePowerAttributes = new EnableRequestAttributes.Builder(true)
                .setConnectType(CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC)
                .setSatelliteEnablementRequestReason(
                        SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_POWER)
                .build();
        mSatelliteEnablementController.requestSatelliteEnabled(
                SUB_ID, enablePowerAttributes, mExecutor, mResultListener);
        // Now satellite should be enabled as all reasons are cleared.
        assertTrue(mSatelliteEnablementController.isSatelliteEnabled(
                SUB_ID, CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC));
    }

    @Test
    public void testSatelliteRemainsDisabled_whenDisabledForPowerSaving() {
        // Disable for POWER reason
        EnableRequestAttributes disableAttributes = new EnableRequestAttributes.Builder(false)
                .setConnectType(CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC)
                .setSatelliteEnablementRequestReason(
                        SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_POWER)
                .build();
        mSatelliteEnablementController.requestSatelliteEnabled(
                SUB_ID, disableAttributes, mExecutor, mResultListener);
        verify(mAutoEnablementController).disableSatellite(
                disableAttributes, mExecutor, mResultListener);
        assertFalse(mSatelliteEnablementController.isSatelliteEnabled(
                SUB_ID, CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC));

        // Try to enable for USER reason
        EnableRequestAttributes enableAttributes = new EnableRequestAttributes.Builder(true)
                .setConnectType(CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC)
                .setSatelliteEnablementRequestReason(
                        SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_USER)
                .build();
        mSatelliteEnablementController.requestSatelliteEnabled(
                SUB_ID, enableAttributes, mExecutor, mResultListener);
        // Should still be disabled because of POWER reason
        verify(mAutoEnablementController).disableSatellite(
                enableAttributes, mExecutor, mResultListener);
        assertFalse(mSatelliteEnablementController.isSatelliteEnabled(
                SUB_ID, CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC));

        // Re-enable for POWER reason
        EnableRequestAttributes reEnableAttributes = new EnableRequestAttributes.Builder(true)
                .setConnectType(CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC)
                .setSatelliteEnablementRequestReason(
                        SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_POWER)
                .build();
        mSatelliteEnablementController.requestSatelliteEnabled(
                SUB_ID, reEnableAttributes, mExecutor, mResultListener);
        // Now it should be enabled
        verify(mAutoEnablementController).enableSatellite(
                reEnableAttributes, mExecutor, mResultListener);
        assertTrue(mSatelliteEnablementController.isSatelliteEnabled(
                SUB_ID, CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC));
    }


}
