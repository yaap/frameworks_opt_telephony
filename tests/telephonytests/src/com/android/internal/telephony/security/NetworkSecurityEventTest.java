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

package com.android.internal.telephony.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.telephony.NetworkSecurityEvent;
import android.telephony.ServiceState;

import org.junit.Test;

import java.util.Arrays;

public class NetworkSecurityEventTest {

    private static final int ALERT_CATEGORY = NetworkSecurityEvent.ALERT_CATEGORY_DOWNGRADE;
    private static final int ALERT_STATUS = NetworkSecurityEvent.ALERT_STATUS_DETECTED;
    private static final int[] REASON_CODES = {
        NetworkSecurityEvent.REASON_CODE_DOWNGRADE_FORCED_HANDOVER
    };
    private static final long CELL_ID = 123;
    private static final int PHYS_CELL_ID = 456;
    private static final int ARFCN = 789;
    private static final String PLMN = "101112";
    private static final int RAT = ServiceState.RIL_RADIO_TECHNOLOGY_LTE;
    private static final boolean IS_EMERGENCY = false;

    @Test
    public void testEqualsAndHash() {
        NetworkSecurityEvent event1 =
                new NetworkSecurityEvent(
                        ALERT_CATEGORY,
                        ALERT_STATUS,
                        REASON_CODES,
                        CELL_ID,
                        PHYS_CELL_ID,
                        ARFCN,
                        PLMN,
                        RAT,
                        IS_EMERGENCY);
        NetworkSecurityEvent event2 =
                new NetworkSecurityEvent(
                        ALERT_CATEGORY,
                        ALERT_STATUS,
                        REASON_CODES,
                        CELL_ID,
                        PHYS_CELL_ID,
                        ARFCN,
                        PLMN,
                        RAT,
                        IS_EMERGENCY);

        assertEquals(event1, event2);
        assertEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    public void testNotEquals() {
        NetworkSecurityEvent event =
                new NetworkSecurityEvent(
                        ALERT_CATEGORY,
                        ALERT_STATUS,
                        REASON_CODES,
                        CELL_ID,
                        PHYS_CELL_ID,
                        ARFCN,
                        PLMN,
                        RAT,
                        IS_EMERGENCY);

        assertNotEquals(
                event,
                new NetworkSecurityEvent(
                        NetworkSecurityEvent.ALERT_CATEGORY_IMPRISONMENT,
                        ALERT_STATUS,
                        REASON_CODES,
                        CELL_ID,
                        PHYS_CELL_ID,
                        ARFCN,
                        PLMN,
                        RAT,
                        IS_EMERGENCY));
        assertNotEquals(
                event,
                new NetworkSecurityEvent(
                        ALERT_CATEGORY,
                        NetworkSecurityEvent.ALERT_STATUS_MITIGATED_CELL_BARRED,
                        REASON_CODES,
                        CELL_ID,
                        PHYS_CELL_ID,
                        ARFCN,
                        PLMN,
                        RAT,
                        IS_EMERGENCY));
        assertNotEquals(
                event,
                new NetworkSecurityEvent(
                        ALERT_CATEGORY,
                        ALERT_STATUS,
                        new int[] {
                            NetworkSecurityEvent.REASON_CODE_IMPRISONMENT_CELL_RESELECTION_FAILURE
                        },
                        CELL_ID,
                        PHYS_CELL_ID,
                        ARFCN,
                        PLMN,
                        RAT,
                        IS_EMERGENCY));
        assertNotEquals(
                event,
                new NetworkSecurityEvent(
                        ALERT_CATEGORY,
                        ALERT_STATUS,
                        REASON_CODES,
                        CELL_ID + 1,
                        PHYS_CELL_ID,
                        ARFCN,
                        PLMN,
                        RAT,
                        IS_EMERGENCY));
        assertNotEquals(
                event,
                new NetworkSecurityEvent(
                        ALERT_CATEGORY,
                        ALERT_STATUS,
                        REASON_CODES,
                        CELL_ID,
                        PHYS_CELL_ID + 1,
                        ARFCN,
                        PLMN,
                        RAT,
                        IS_EMERGENCY));
        assertNotEquals(
                event,
                new NetworkSecurityEvent(
                        ALERT_CATEGORY,
                        ALERT_STATUS,
                        REASON_CODES,
                        CELL_ID,
                        PHYS_CELL_ID,
                        ARFCN + 1,
                        PLMN,
                        RAT,
                        IS_EMERGENCY));
        assertNotEquals(
                event,
                new NetworkSecurityEvent(
                        ALERT_CATEGORY,
                        ALERT_STATUS,
                        REASON_CODES,
                        CELL_ID,
                        PHYS_CELL_ID,
                        ARFCN,
                        "101113",
                        RAT,
                        IS_EMERGENCY));
        assertNotEquals(
                event,
                new NetworkSecurityEvent(
                        ALERT_CATEGORY,
                        ALERT_STATUS,
                        REASON_CODES,
                        CELL_ID,
                        PHYS_CELL_ID,
                        ARFCN,
                        PLMN,
                        ServiceState.RIL_RADIO_TECHNOLOGY_GSM,
                        IS_EMERGENCY));
        assertNotEquals(
                event,
                new NetworkSecurityEvent(
                        ALERT_CATEGORY,
                        ALERT_STATUS,
                        REASON_CODES,
                        CELL_ID,
                        PHYS_CELL_ID,
                        ARFCN,
                        PLMN,
                        RAT,
                        !IS_EMERGENCY));
    }

    @Test
    public void testGetters() {
        NetworkSecurityEvent event =
                new NetworkSecurityEvent(
                        ALERT_CATEGORY,
                        ALERT_STATUS,
                        REASON_CODES,
                        CELL_ID,
                        PHYS_CELL_ID,
                        ARFCN,
                        PLMN,
                        RAT,
                        IS_EMERGENCY);

        assertEquals(ALERT_CATEGORY, event.getAlertCategory());
        assertEquals(ALERT_STATUS, event.getAlertStatus());
        assertTrue(Arrays.equals(REASON_CODES, event.getReasonCodes()));
        assertEquals(CELL_ID, event.getCellId());
        assertEquals(PHYS_CELL_ID, event.getPhysicalCellId());
        assertEquals(ARFCN, event.getArfcn());
        assertEquals(PLMN, event.getPlmn());
        assertEquals(RAT, event.getRat());
        assertEquals(IS_EMERGENCY, event.isEmergency());
    }

    @Test
    public void testParcel() {
        NetworkSecurityEvent event =
                new NetworkSecurityEvent(
                        ALERT_CATEGORY,
                        ALERT_STATUS,
                        REASON_CODES,
                        CELL_ID,
                        PHYS_CELL_ID,
                        ARFCN,
                        PLMN,
                        RAT,
                        IS_EMERGENCY);

        Parcel p = Parcel.obtain();
        event.writeToParcel(p, 0);
        p.setDataPosition(0);

        NetworkSecurityEvent fromParcel = NetworkSecurityEvent.CREATOR.createFromParcel(p);
        assertEquals(event.getAlertCategory(), fromParcel.getAlertCategory());
        assertEquals(event.getAlertStatus(), fromParcel.getAlertStatus());
        assertTrue(Arrays.equals(event.getReasonCodes(), fromParcel.getReasonCodes()));
        assertEquals(event.getCellId(), fromParcel.getCellId());
        assertEquals(event.getPhysicalCellId(), fromParcel.getPhysicalCellId());
        assertEquals(event.getArfcn(), fromParcel.getArfcn());
        assertEquals(event.getPlmn(), fromParcel.getPlmn());
        assertEquals(event.getRat(), fromParcel.getRat());
        assertEquals(event.isEmergency(), fromParcel.isEmergency());
    }
}
