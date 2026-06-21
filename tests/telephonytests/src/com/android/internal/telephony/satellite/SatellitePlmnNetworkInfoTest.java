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

import static android.telephony.satellite.SatelliteManager.NT_RADIO_TECHNOLOGY_LTE_DTC;
import static android.telephony.satellite.SatelliteManager.NT_RADIO_TECHNOLOGY_NB_IOT_NTN;
import static android.telephony.satellite.SatelliteManager.NT_RADIO_TECHNOLOGY_NR_NTN;
import static android.telephony.satellite.SatelliteManager.NT_RADIO_TECHNOLOGY_UNKNOWN;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.telephony.AccessNetworkConstants;
import android.telephony.SubscriptionManager;
import android.testing.AndroidTestingRunner;

import com.android.internal.telephony.TelephonyTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@RunWith(AndroidTestingRunner.class)
public class SatellitePlmnNetworkInfoTest extends TelephonyTest {
    private static final String TAG = "SatellitePlmnNetworkInfoTest";

    private static final String PLMN_ALLOWED_1 = "11111";
    private static final String PLMN_DISALLOWED = "67890";
    private static final int[] ARFCNS = {100, 200};
    private static final int SUB_ID = 1;

    @Mock
    private SatelliteController mMockSatelliteController;

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        MockitoAnnotations.initMocks(this);
        replaceInstance(SatelliteController.class, "sInstance", null, mMockSatelliteController);
        logd(TAG + " Setup!");
    }

    @After
    public void tearDown() throws Exception {
        logd(TAG + " tearDown");
        super.tearDown();
    }

    /**
     * Test NetworkInfo Builder and Getter
     */
    @Test
    public void testNetworkInfoBuilder() {
        SatellitePlmnNetworkInfo.NetworkInfo info =
                new SatellitePlmnNetworkInfo.NetworkInfo.Builder(PLMN_ALLOWED_1)
                .setArfcns(ARFCNS)
                .setAccessNetwork(AccessNetworkConstants.AccessNetworkType.EUTRAN)
                .setSatelliteTechnology(NT_RADIO_TECHNOLOGY_NB_IOT_NTN)
                .setHasSamePriorityAsTn(true)
                .build();

        assertEquals(PLMN_ALLOWED_1, info.getPlmn());
        assertArrayEquals(ARFCNS, info.getArfcns());
        assertEquals(AccessNetworkConstants.AccessNetworkType.EUTRAN, info.getAccessNetwork());
        assertEquals(NT_RADIO_TECHNOLOGY_NB_IOT_NTN, info.getSatelliteTechnology());
        assertTrue(info.hasSamePriorityAsTn());
    }

    /**
     * Test for default value
     */
    @Test
    public void testNetworkInfoCreateWithDefaults() {
        SatellitePlmnNetworkInfo.NetworkInfo info =
                SatellitePlmnNetworkInfo.NetworkInfo.createWithDefaults(PLMN_ALLOWED_1);

        assertEquals(PLMN_ALLOWED_1, info.getPlmn());
        assertEquals(0, info.getArfcns().length);
        assertEquals(AccessNetworkConstants.AccessNetworkType.UNKNOWN, info.getAccessNetwork());
        assertEquals(NT_RADIO_TECHNOLOGY_UNKNOWN, info.getSatelliteTechnology());
        assertFalse(info.hasSamePriorityAsTn());
    }

    /**
     * Tests the conversion of the internal {@link SatellitePlmnNetworkInfo.NetworkInfo}
     * data to the HAL-defined {@link android.hardware.radio.network.NetworkInfo} object.
     */
    @Test
    public void testToHalNetworkInfo() {
        SatellitePlmnNetworkInfo.NetworkInfo info =
                new SatellitePlmnNetworkInfo.NetworkInfo.Builder(PLMN_ALLOWED_1)
                        .setArfcns(ARFCNS)
                        .setAccessNetwork(AccessNetworkConstants.AccessNetworkType.EUTRAN)
                        .setSatelliteTechnology(NT_RADIO_TECHNOLOGY_NB_IOT_NTN)
                        .build();

        android.hardware.radio.network.NetworkInfo halInfo = info.toHalNetworkInfo();

        assertEquals(PLMN_ALLOWED_1, halInfo.plmn);
        assertArrayEquals(ARFCNS, halInfo.arfcns);
        assertEquals(AccessNetworkConstants.AccessNetworkType.EUTRAN, halInfo.accessNetwork);
        assertEquals(NT_RADIO_TECHNOLOGY_NB_IOT_NTN, halInfo.satelliteTechnology);
    }

    /**
     * Verifies the integrity of {@link SatellitePlmnNetworkInfo} when initialized with
     * both allowed and disallowed PLMN lists.
     */
    @Test
    public void testSatellitePlmnNetworkInfoLists() {
        SatellitePlmnNetworkInfo.NetworkInfo allowed =
                SatellitePlmnNetworkInfo.NetworkInfo.createWithDefaults(PLMN_ALLOWED_1);
        SatellitePlmnNetworkInfo.NetworkInfo disallowed =
                SatellitePlmnNetworkInfo.NetworkInfo.createWithDefaults(PLMN_DISALLOWED);

        SatellitePlmnNetworkInfo satelliteInfo = new SatellitePlmnNetworkInfo(
                List.of(allowed), List.of(disallowed));

        assertEquals(1, satelliteInfo.getAllowedPlmns().size());
        assertEquals(PLMN_ALLOWED_1, satelliteInfo.getAllowedPlmns().get(0).getPlmn());
        assertEquals(1, satelliteInfo.getDisallowedPlmnsAsArray().length);
    }

    /**
     * Tests the comprehensive conversion of the {@link SatellitePlmnNetworkInfo} container
     * to the HAL-defined {@link android.hardware.radio.network.SatelliteNetworkInfo} structure.
     */
    @Test
    public void testToHalSatelliteNetworkInfo() {
        SatellitePlmnNetworkInfo.NetworkInfo allowed =
                SatellitePlmnNetworkInfo.NetworkInfo.createWithDefaults(PLMN_ALLOWED_1);
        SatellitePlmnNetworkInfo.NetworkInfo disallowed =
                SatellitePlmnNetworkInfo.NetworkInfo.createWithDefaults(PLMN_DISALLOWED);

        SatellitePlmnNetworkInfo satelliteInfo = new SatellitePlmnNetworkInfo(
                List.of(allowed), List.of(disallowed));

        android.hardware.radio.network.SatelliteNetworkInfo halSatelliteInfo =
                satelliteInfo.toHalSatelliteNetworkInfo();

        assertEquals(1, halSatelliteInfo.allowedPlmns.length);
        assertEquals(PLMN_ALLOWED_1, halSatelliteInfo.allowedPlmns[0].plmn);
        assertEquals(1, halSatelliteInfo.disallowedPlmns.length);
        assertEquals(PLMN_DISALLOWED, halSatelliteInfo.disallowedPlmns[0].plmn);
    }

    /**
     * Test {@link SatellitePlmnNetworkInfo#fromPlmn}, which is responsible for creating
     * a {@link SatellitePlmnNetworkInfo} instance from raw PLMN string lists.
     */
    @Test
    public void testFromPlmn() {
        when(mMockSatelliteController
                .getSupportedSatelliteTechnologies(anyInt(), eq(PLMN_ALLOWED_1)))
                .thenReturn(List.of(NT_RADIO_TECHNOLOGY_LTE_DTC, NT_RADIO_TECHNOLOGY_NR_NTN));

        List<String> allowedPlmns = List.of(PLMN_ALLOWED_1);
        List<String> disallowedPlmns = List.of(PLMN_DISALLOWED);

        SatellitePlmnNetworkInfo info =
                SatellitePlmnNetworkInfo.fromPlmn(SubscriptionManager.INVALID_SUBSCRIPTION_ID,
                        allowedPlmns, disallowedPlmns);
        assertEquals(0, info.getDisallowedPlmns().size());
        assertEquals(0, info.getAllowedPlmns().size());

        info = SatellitePlmnNetworkInfo.fromPlmn(SUB_ID, allowedPlmns, disallowedPlmns);

        assertEquals(1, info.getDisallowedPlmns().size());
        assertEquals(PLMN_DISALLOWED, info.getDisallowedPlmns().get(0).getPlmn());
        assertEquals(2, info.getAllowedPlmns().size());
        assertEquals(PLMN_ALLOWED_1, info.getAllowedPlmns().get(0).getPlmn());
    }
}
