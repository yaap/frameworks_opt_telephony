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

import static org.mockito.Mockito.verify;

import android.platform.test.annotations.RequiresFlagsEnabled;
import com.android.internal.telephony.flags.Flags;
import com.android.internal.telephony.satellite.SatelliteEnablementStrategy;
import android.telephony.satellite.EnableRequestAttributes;
import android.telephony.satellite.SatelliteManager;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

@RequiresFlagsEnabled(Flags.FLAG_SATELLITE_UPSELL_26Q4)
public class SatelliteEnablementStrategyTest {

    @Mock
    private SatelliteEnablementStrategy mSatelliteEnablementStrategy;
    @Mock
    private Consumer<Integer> mResultListener;

    private Executor mExecutor = Runnable::run;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testEnableSatellite() {
        EnableRequestAttributes attributes = new EnableRequestAttributes.Builder(true).build();
        mSatelliteEnablementStrategy.enableSatellite(attributes, mExecutor, mResultListener);
        verify(mSatelliteEnablementStrategy).enableSatellite(attributes, mExecutor,
                mResultListener);
    }
}
