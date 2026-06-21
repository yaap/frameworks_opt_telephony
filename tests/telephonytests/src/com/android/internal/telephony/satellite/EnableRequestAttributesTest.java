/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.telephony.CarrierConfigManager;
import android.telephony.satellite.EnableRequestAttributes;
import android.telephony.satellite.SatelliteManager;

import org.junit.Test;

public class EnableRequestAttributesTest {

    @Test
    public void testBuilder() {
        EnableRequestAttributes.Builder builder = new EnableRequestAttributes.Builder(true);
        builder.setDemoMode(true);
        builder.setEmergencyMode(false);
        builder.setConnectType(CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_MANUAL);
        builder.setSatelliteEnablementRequestReason(
                SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_USER);
        builder.setPrioritizedScanningRequired(false);

        EnableRequestAttributes attributes = builder.build();

        assertTrue(attributes.isEnabled());
        assertTrue(attributes.isDemoMode());
        assertFalse(attributes.isEmergencyMode());
        assertEquals(CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_MANUAL,
                attributes.getConnectType());
        assertEquals(SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_USER,
                attributes.getSatelliteEnablementRequestReason());
        assertFalse(attributes.isPrioritizedScanningRequired());
    }
}
