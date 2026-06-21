/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.platform.test.flag.junit.SetFlagsRule;
import android.timezone.MobileCountries;
import android.timezone.TelephonyNetworkFinder;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.telephony.MccTable.MccMnc;
import com.android.internal.telephony.flags.Flags;
import com.android.internal.telephony.util.LocaleUtils;

import org.junit.Rule;
import org.junit.Test;

import java.util.Locale;

public class MccTableTest {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @SmallTest
    @Test
    public void testCountryCodeForMcc() throws Exception {
        checkMccLookupWithNoMnc("lu", "270");
        checkMccLookupWithNoMnc("gr", "202");
        checkMccLookupWithNoMnc("fk", "750");
        checkMccLookupWithNoMnc("mg", "646");
        checkMccLookupWithNoMnc("us", "314");
        checkMccLookupWithNoMnc("", "300");  // mcc not defined, hence default
        checkMccLookupWithNoMnc("", "0");    // mcc not defined, hence default
        checkMccLookupWithNoMnc("", "2000"); // mcc not defined, hence default
    }

    private void checkMccLookupWithNoMnc(String expectedCountryIsoCode, String mcc) {
        assertEquals(expectedCountryIsoCode, MccTable.countryCodeForMcc(mcc));
        assertEquals(expectedCountryIsoCode,
                MccTable.geoCountryCodeForMccMnc(new MccMnc(mcc, "999")));
    }

    @SmallTest
    @Test
    public void testGeoCountryCodeForMccMnc() throws Exception {
        // This test is possibly fragile as this data is configurable.
        assertEquals("gu", MccTable.geoCountryCodeForMccMnc(new MccMnc("310", "370")));
    }

    @SmallTest
    @Test
    public void testLang() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_MCC_MNC_LOCALE_RESOLUTION);
        assertEquals("en", LocaleUtils.defaultLanguageForMccMnc(311, null));
        assertEquals("de", LocaleUtils.defaultLanguageForMccMnc(232, null));
        assertEquals("cs", LocaleUtils.defaultLanguageForMccMnc(230, null));
        assertEquals("nl", LocaleUtils.defaultLanguageForMccMnc(204, null));
        assertEquals("is", LocaleUtils.defaultLanguageForMccMnc(274, null));
        assertEquals("sv", LocaleUtils.defaultLanguageForMccMnc(244, "14")); // ALCOM (Åland)
        assertEquals("en", LocaleUtils.defaultLanguageForMccMnc(234, "03")); // Jersey
        // mcc not defined, hence default
        assertNull(LocaleUtils.defaultLanguageForMccMnc(0, null));
        // mcc not defined, hence default
        assertNull(LocaleUtils.defaultLanguageForMccMnc(2000, null));
    }

    @SmallTest
    @Test
    public void testLang_India() throws Exception {
        assertEquals("en", LocaleUtils.defaultLanguageForMccMnc(404, null));
        assertEquals("en", LocaleUtils.defaultLanguageForMccMnc(405, null));
        assertEquals("en", LocaleUtils.defaultLanguageForMccMnc(406, null));
    }

    @SmallTest
    @Test
    public void testLocale() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_MCC_MNC_LOCALE_RESOLUTION);
        assertEquals(Locale.forLanguageTag("en-CA"),
                LocaleUtils.getLocaleFromMccMnc(getContext(), 302, null, null));
        assertEquals(Locale.forLanguageTag("en-GB"),
                LocaleUtils.getLocaleFromMccMnc(getContext(), 234, null, null));
        assertEquals(Locale.forLanguageTag("en-US"),
                LocaleUtils.getLocaleFromMccMnc(getContext(), 0, null, "en"));
        assertEquals(Locale.forLanguageTag("zh-HK"),
                LocaleUtils.getLocaleFromMccMnc(getContext(), 454, null, null));
        assertEquals(Locale.forLanguageTag("en-HK"),
                LocaleUtils.getLocaleFromMccMnc(getContext(), 454, null, "en"));
        assertEquals(Locale.forLanguageTag("zh-TW"),
                LocaleUtils.getLocaleFromMccMnc(getContext(), 466, null, null));
        // ALCOM (Åland): MCC 244, MNC 14 -> Country "ax" -> Swedish
        assertEquals(Locale.forLanguageTag("sv-AX"),
                LocaleUtils.getLocaleFromMccMnc(getContext(), 244, "14", null));
        assertEquals(Locale.forLanguageTag("en-JE"),
                LocaleUtils.getLocaleFromMccMnc(getContext(), 234, "03", null)); // Jersey
    }

    private Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    @SmallTest
    @Test
    public void testSmDigits() throws Exception {
        assertEquals(3, MccTable.smallestDigitsMccForMnc(312));
        assertEquals(2, MccTable.smallestDigitsMccForMnc(430));
        assertEquals(3, MccTable.smallestDigitsMccForMnc(365));
        assertEquals(2, MccTable.smallestDigitsMccForMnc(536));
        // sd not defined, hence default
        assertEquals(2, MccTable.smallestDigitsMccForMnc(352));
        // mcc not defined, hence default
        assertEquals(2, MccTable.smallestDigitsMccForMnc(0));
        // mcc not defined, hence default
        assertEquals(2, MccTable.smallestDigitsMccForMnc(2000));
    }

    @SmallTest
    @Test
    public void testNullMcc() throws Exception {
        assertEquals("", MccTable.countryCodeForMcc(null));
    }
}
