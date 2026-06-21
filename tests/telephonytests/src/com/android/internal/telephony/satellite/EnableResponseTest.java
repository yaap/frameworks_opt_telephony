/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.telephony.satellite.EnableResponse;
import android.telephony.satellite.SatelliteManager;

import org.junit.Test;

public class EnableResponseTest {

    @Test
    public void testConstructorAndGetters() {
        boolean isEnabled = true;
        boolean isEmergencyMode = false;
        boolean isDemoMode = true;
        int[] requestReasons = {
                SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_UNKNOWN,
                SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_PURCHASE,
                SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_USER,
                SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_POWER,
                SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_CARRIER_CONFIG_UPDATE,
                SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_ENTITLEMENT};

        EnableResponse response = new EnableResponse(isEnabled, isEmergencyMode, isDemoMode,
                requestReasons);

        assertTrue(response.isEnabled());
        assertFalse(response.isEmergencyMode());
        assertTrue(response.isDemoMode());
        assertArrayEquals(requestReasons, response.getSatelliteEnablementRequestReasons());
    }
}
