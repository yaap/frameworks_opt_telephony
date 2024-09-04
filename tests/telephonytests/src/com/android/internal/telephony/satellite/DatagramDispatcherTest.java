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

package com.android.internal.telephony.satellite;

import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_MODEM_TIMEOUT;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_SUCCESS;

import static com.android.internal.telephony.satellite.DatagramController.SATELLITE_ALIGN_TIMEOUT;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Looper;
import android.os.Message;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.satellite.SatelliteDatagram;
import android.telephony.satellite.SatelliteManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.R;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.satellite.metrics.ControllerMetricsStats;
import com.android.internal.telephony.satellite.metrics.SessionMetricsStats;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class DatagramDispatcherTest extends TelephonyTest {
    private static final String TAG = "DatagramDispatcherTest";
    private static final int SUB_ID = 0;
    private static final int DATAGRAM_TYPE1 = SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE;
    private static final int DATAGRAM_TYPE2 = SatelliteManager.DATAGRAM_TYPE_LOCATION_SHARING;
    private static final int DATAGRAM_TYPE3 = SatelliteManager.DATAGRAM_TYPE_KEEP_ALIVE;
    private static final int DATAGRAM_TYPE4 =
            SatelliteManager.DATAGRAM_TYPE_LAST_SOS_MESSAGE_STILL_NEED_HELP;
    private static final int DATAGRAM_TYPE5 =
            SatelliteManager.DATAGRAM_TYPE_LAST_SOS_MESSAGE_NO_HELP_NEEDED;

    private static final String TEST_MESSAGE = "This is a test datagram message";
    private static final long TEST_EXPIRE_TIMER_SATELLITE_ALIGN = TimeUnit.SECONDS.toMillis(1);
    private static final int TEST_WAIT_FOR_DATAGRAM_SENDING_RESPONSE_TIMEOUT_MILLIS =
            (int) TimeUnit.SECONDS.toMillis(180);
    private static final long TEST_DATAGRAM_WAIT_FOR_CONNECTED_STATE_TIMEOUT_MILLIS =
            TimeUnit.SECONDS.toMillis(60);
    private static final Long TIMEOUT_DATAGRAM_DELAY_IN_DEMO_MODE = TimeUnit.SECONDS.toMillis(10);
    private static final long
            TEST_DATAGRAM_WAIT_FOR_CONNECTED_STATE_FOR_LAST_MESSAGE_TIMEOUT_MILLIS =
            TimeUnit.SECONDS.toMillis(60);
    private static final int
            TEST_WAIT_FOR_DATAGRAM_SENDING_RESPONSE_FOR_LAST_MESSAGE_TIMEOUT_MILLIS =
            (int) TimeUnit.SECONDS.toMillis(60);

    private TestDatagramDispatcher mDatagramDispatcherUT;

    @Mock private DatagramController mMockDatagramController;
    @Mock private DatagramReceiver mMockDatagramReceiver;
    @Mock private SatelliteModemInterface mMockSatelliteModemInterface;
    @Mock private ControllerMetricsStats mMockControllerMetricsStats;
    @Mock private SatelliteSessionController mMockSatelliteSessionController;
    @Mock private SessionMetricsStats mMockSessionMetricsStats;

    /** Variables required to send datagram in the unit tests. */
    LinkedBlockingQueue<Integer> mResultListener;
    SatelliteDatagram mDatagram;
    InOrder mInOrder;

    private static final long TIMEOUT = 500;
    private List<Integer> mIntegerConsumerResult = new ArrayList<>();
    private Semaphore mIntegerConsumerSemaphore = new Semaphore(0);
    private Consumer<Integer> mIntegerConsumer = integer -> {
        logd("mIntegerConsumer: integer=" + integer);
        mIntegerConsumerResult.add(integer);
        try {
            mIntegerConsumerSemaphore.release();
        } catch (Exception ex) {
            loge("mIntegerConsumer: Got exception in releasing semaphore, ex=" + ex);
        }
    };

    private final int mConfigSendSatelliteDatagramToModemInDemoMode =
            R.bool.config_send_satellite_datagram_to_modem_in_demo_mode;

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        MockitoAnnotations.initMocks(this);
        logd(TAG + " Setup!");

        replaceInstance(DatagramController.class, "sInstance", null,
                mMockDatagramController);
        replaceInstance(DatagramReceiver.class, "sInstance", null,
                mMockDatagramReceiver);
        replaceInstance(SatelliteModemInterface.class, "sInstance", null,
                mMockSatelliteModemInterface);
        replaceInstance(ControllerMetricsStats.class, "sInstance", null,
                mMockControllerMetricsStats);
        replaceInstance(SatelliteSessionController.class, "sInstance", null,
                mMockSatelliteSessionController);
        replaceInstance(SessionMetricsStats.class, "sInstance", null,
                mMockSessionMetricsStats);

        when(mFeatureFlags.oemEnabledSatelliteFlag()).thenReturn(true);
        when(mFeatureFlags.satellitePersistentLogging()).thenReturn(true);
        mDatagramDispatcherUT = new TestDatagramDispatcher(mContext, Looper.myLooper(),
                mFeatureFlags,
                mMockDatagramController);

        mResultListener = new LinkedBlockingQueue<>(1);
        mDatagram = new SatelliteDatagram(TEST_MESSAGE.getBytes());
        mInOrder = inOrder(mMockDatagramController);
        when(mMockDatagramController.isPollingInIdleState()).thenReturn(true);
    }

    @After
    public void tearDown() throws Exception {
        logd(TAG + " tearDown");
        mDatagramDispatcherUT.destroy();
        mDatagramDispatcherUT = null;
        mResultListener = null;
        mDatagram = null;
        mInOrder = null;
        super.tearDown();
    }

    @Test
    public void testSendSatelliteDatagram_success() throws  Exception {
        doAnswer(invocation -> {
            Message message = (Message) invocation.getArguments()[3];

            mDatagramDispatcherUT.obtainMessage(2 /*EVENT_SEND_SATELLITE_DATAGRAM_DONE*/,
                    new AsyncResult(message.obj, null, null))
                    .sendToTarget();
            return null;
        }).when(mMockSatelliteModemInterface).sendSatelliteDatagram(any(SatelliteDatagram.class),
                anyBoolean(), anyBoolean(), any(Message.class));

        int[] sosDatagramTypes = {DATAGRAM_TYPE1, DATAGRAM_TYPE4, DATAGRAM_TYPE5};
        for (int datagramType : sosDatagramTypes) {
            clearInvocations(mMockDatagramController);
            clearInvocations(mMockSessionMetricsStats);
            clearInvocations(mMockSatelliteModemInterface);
            doReturn(true).when(mMockDatagramController)
                    .needsWaitingForSatelliteConnected(eq(datagramType));
            when(mMockDatagramController.getDatagramWaitTimeForConnectedState(eq(false)))
                    .thenReturn(TEST_DATAGRAM_WAIT_FOR_CONNECTED_STATE_TIMEOUT_MILLIS);
            when(mMockDatagramController.getDatagramWaitTimeForConnectedState(eq(true)))
                    .thenReturn(
                            TEST_DATAGRAM_WAIT_FOR_CONNECTED_STATE_FOR_LAST_MESSAGE_TIMEOUT_MILLIS);
            mResultListener.clear();

            mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, datagramType, mDatagram,
                    true, mResultListener::offer);
            processAllMessages();
            mInOrder.verify(mMockDatagramController)
                    .needsWaitingForSatelliteConnected(eq(datagramType));
            mInOrder.verify(mMockDatagramController).updateSendStatus(eq(SUB_ID), eq(datagramType),
                    eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_WAITING_TO_CONNECT),
                    eq(1),
                    eq(SATELLITE_RESULT_SUCCESS));
            mInOrder.verify(mMockDatagramController).getDatagramWaitTimeForConnectedState(
                    eq(SatelliteServiceUtils.isLastSosMessage(datagramType)));
            verifyZeroInteractions(mMockSatelliteModemInterface);
            assertTrue(mDatagramDispatcherUT.isDatagramWaitForConnectedStateTimerStarted());

            doReturn(false).when(mMockDatagramController)
                    .needsWaitingForSatelliteConnected(eq(datagramType));
            mDatagramDispatcherUT.onSatelliteModemStateChanged(
                    SatelliteManager.SATELLITE_MODEM_STATE_CONNECTED);
            processAllMessages();

            mInOrder.verify(mMockDatagramController).isPollingInIdleState();
            mInOrder.verify(mMockDatagramController)
                    .needsWaitingForSatelliteConnected(eq(datagramType));
            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(eq(SUB_ID), eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING), eq(1),
                            eq(SATELLITE_RESULT_SUCCESS));
            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(eq(SUB_ID), eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS),
                            eq(0),
                            eq(SATELLITE_RESULT_SUCCESS));
            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(eq(SUB_ID), eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE), eq(0),
                            eq(SATELLITE_RESULT_SUCCESS));
            verifyNoMoreInteractions(mMockDatagramController);
            verify(mMockSessionMetricsStats, times(1))
                    .addCountOfSuccessfulOutgoingDatagram(eq(datagramType));
            verify(mMockSatelliteModemInterface, times(1)).sendSatelliteDatagram(
                    any(SatelliteDatagram.class), anyBoolean(), anyBoolean(), any(Message.class));
            assertFalse(mDatagramDispatcherUT.isDatagramWaitForConnectedStateTimerStarted());

            assertThat(mResultListener.peek()).isEqualTo(SATELLITE_RESULT_SUCCESS);

            clearInvocations(mMockSatelliteModemInterface);
            clearInvocations(mMockDatagramController);
            clearInvocations(mMockSessionMetricsStats);
            mResultListener.clear();
            doReturn(true).when(mMockDatagramController)
                    .needsWaitingForSatelliteConnected(eq(datagramType));
            mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, datagramType, mDatagram,
                    true, mResultListener::offer);
            processAllMessages();
            verifyZeroInteractions(mMockSatelliteModemInterface);
            mInOrder.verify(mMockDatagramController)
                    .needsWaitingForSatelliteConnected(eq(datagramType));
            mInOrder.verify(mMockDatagramController).getDatagramWaitTimeForConnectedState(
                    eq(SatelliteServiceUtils.isLastSosMessage(datagramType)));
            assertTrue(mDatagramDispatcherUT.isDatagramWaitForConnectedStateTimerStarted());

            moveTimeForward(TEST_DATAGRAM_WAIT_FOR_CONNECTED_STATE_TIMEOUT_MILLIS);
            processAllMessages();
            verifyZeroInteractions(mMockSatelliteModemInterface);
            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(eq(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID),
                            eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED),
                            eq(1),
                            eq(SatelliteManager.SATELLITE_RESULT_NOT_REACHABLE));
            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(eq(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID),
                            eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE), eq(0),
                            eq(SATELLITE_RESULT_SUCCESS));
            assertEquals(1, mResultListener.size());
            assertThat(mResultListener.peek()).isEqualTo(
                    SatelliteManager.SATELLITE_RESULT_NOT_REACHABLE);
            assertFalse(mDatagramDispatcherUT.isDatagramWaitForConnectedStateTimerStarted());
            verify(mMockSessionMetricsStats, times(1))
                    .addCountOfFailedOutgoingDatagram(anyInt(), anyInt());

            mResultListener.clear();
            mDatagramDispatcherUT.onSatelliteModemStateChanged(
                    SatelliteManager.SATELLITE_MODEM_STATE_CONNECTED);
            processAllMessages();
            verifyZeroInteractions(mMockSatelliteModemInterface);
            assertEquals(0, mResultListener.size());

            clearInvocations(mMockSatelliteModemInterface);
            clearInvocations(mMockDatagramController);
            clearInvocations(mMockSessionMetricsStats);
            mResultListener.clear();
            mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, datagramType, mDatagram,
                    true, mResultListener::offer);
            processAllMessages();
            verifyZeroInteractions(mMockSatelliteModemInterface);
            mInOrder.verify(mMockDatagramController)
                    .needsWaitingForSatelliteConnected(eq(datagramType));
            mInOrder.verify(mMockDatagramController).getDatagramWaitTimeForConnectedState(
                    eq(SatelliteServiceUtils.isLastSosMessage(datagramType)));
            assertTrue(mDatagramDispatcherUT.isDatagramWaitForConnectedStateTimerStarted());
            assertEquals(0, mResultListener.size());

            mDatagramDispatcherUT.onSatelliteModemStateChanged(
                    SatelliteManager.SATELLITE_MODEM_STATE_OFF);
            processAllMessages();
            verifyZeroInteractions(mMockSatelliteModemInterface);
            assertEquals(1, mResultListener.size());
            assertThat(mResultListener.peek()).isEqualTo(
                    SatelliteManager.SATELLITE_RESULT_REQUEST_ABORTED);
            assertFalse(mDatagramDispatcherUT.isDatagramWaitForConnectedStateTimerStarted());
            verify(mMockSessionMetricsStats, times(1))
                    .addCountOfFailedOutgoingDatagram(anyInt(), anyInt());
        }
    }

    @Test
    public void testSendSatelliteDatagram_timeout() throws  Exception {
        when(mMockDatagramController.getDatagramWaitTimeForConnectedState(eq(false)))
                .thenReturn(TEST_DATAGRAM_WAIT_FOR_CONNECTED_STATE_TIMEOUT_MILLIS);
        when(mMockDatagramController.getDatagramWaitTimeForConnectedState(eq(true)))
                .thenReturn(TEST_DATAGRAM_WAIT_FOR_CONNECTED_STATE_FOR_LAST_MESSAGE_TIMEOUT_MILLIS);
        mContextFixture.putIntResource(
                R.integer.config_wait_for_datagram_sending_response_timeout_millis,
                TEST_WAIT_FOR_DATAGRAM_SENDING_RESPONSE_TIMEOUT_MILLIS);
        mContextFixture.putIntResource(
                R.integer.config_wait_for_datagram_sending_response_for_last_message_timeout_millis,
                TEST_WAIT_FOR_DATAGRAM_SENDING_RESPONSE_FOR_LAST_MESSAGE_TIMEOUT_MILLIS);
        mResultListener.clear();
        int[] sosDatagramTypes = {DATAGRAM_TYPE1, DATAGRAM_TYPE4, DATAGRAM_TYPE5};
        for (int datagramType : sosDatagramTypes) {
            doAnswer(invocation -> {
                Message message = (Message) invocation.getArguments()[3];

                mDatagramDispatcherUT.obtainMessage(2 /*EVENT_SEND_SATELLITE_DATAGRAM_DONE*/,
                                new AsyncResult(message.obj, null, null))
                        .sendToTarget();

                // DatagramDispatcher should ignore the second EVENT_SEND_SATELLITE_DATAGRAM_DONE
                mDatagramDispatcherUT.obtainMessage(2 /*EVENT_SEND_SATELLITE_DATAGRAM_DONE*/,
                                new AsyncResult(message.obj, null, null))
                        .sendToTarget();

                return null;
            }).when(mMockSatelliteModemInterface).sendSatelliteDatagram(
                    any(SatelliteDatagram.class),
                    anyBoolean(), anyBoolean(), any(Message.class));
            doReturn(false).when(mMockDatagramController)
                    .needsWaitingForSatelliteConnected(eq(datagramType));

            mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, datagramType, mDatagram,
                    true, mResultListener::offer);
            processAllMessages();
            mInOrder.verify(mMockDatagramController)
                    .needsWaitingForSatelliteConnected(eq(datagramType));
            mInOrder.verify(mMockDatagramController).isPollingInIdleState();
            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(eq(SUB_ID), eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING), eq(1),
                            eq(SATELLITE_RESULT_SUCCESS));
            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(eq(SUB_ID), eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS),
                            eq(0),
                            eq(SATELLITE_RESULT_SUCCESS));
            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(eq(SUB_ID), eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE), eq(0),
                            eq(SATELLITE_RESULT_SUCCESS));
            verifyNoMoreInteractions(mMockDatagramController);
            verify(mMockSatelliteModemInterface, times(1)).sendSatelliteDatagram(
                    any(SatelliteDatagram.class), anyBoolean(), anyBoolean(), any(Message.class));
            assertThat(mResultListener.peek()).isEqualTo(SATELLITE_RESULT_SUCCESS);
            verify(mMockSessionMetricsStats, times(1))
                    .addCountOfSuccessfulOutgoingDatagram(anyInt());
            clearInvocations(mMockSatelliteModemInterface);
            clearInvocations(mMockDatagramController);
            clearInvocations(mMockSessionMetricsStats);
            mResultListener.clear();

            // No response for the send request from modem
            doNothing().when(mMockSatelliteModemInterface).sendSatelliteDatagram(
                    any(SatelliteDatagram.class), anyBoolean(), anyBoolean(), any(Message.class));

            mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, datagramType, mDatagram,
                    true, mResultListener::offer);
            processAllMessages();
            mInOrder.verify(mMockDatagramController)
                    .needsWaitingForSatelliteConnected(eq(datagramType));
            mInOrder.verify(mMockDatagramController).isPollingInIdleState();
            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(eq(SUB_ID), eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING), eq(1),
                            eq(SATELLITE_RESULT_SUCCESS));
            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(eq(SUB_ID), eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED),
                            eq(1),
                            eq(SATELLITE_RESULT_MODEM_TIMEOUT));
            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(eq(SUB_ID), eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE), eq(0),
                            eq(SATELLITE_RESULT_SUCCESS));
            verifyNoMoreInteractions(mMockDatagramController);
            verify(mMockSatelliteModemInterface, times(1)).sendSatelliteDatagram(
                    any(SatelliteDatagram.class), anyBoolean(), anyBoolean(), any(Message.class));
            verify(mMockSatelliteModemInterface).abortSendingSatelliteDatagrams(any(Message.class));
            assertThat(mResultListener.peek()).isEqualTo(SATELLITE_RESULT_MODEM_TIMEOUT);
            verify(mMockSessionMetricsStats, times(1))
                    .addCountOfFailedOutgoingDatagram(anyInt(), anyInt());

            clearInvocations(mMockSatelliteModemInterface);
            clearInvocations(mMockDatagramController);
            clearInvocations(mMockSessionMetricsStats);
            mResultListener.clear();
        }
    }

    @Test
    public void testSendSatelliteDatagram_failure() throws  Exception {
        doAnswer(invocation -> {
            Message message = (Message) invocation.getArguments()[3];

            mDatagramDispatcherUT.obtainMessage(2 /*EVENT_SEND_SATELLITE_DATAGRAM_DONE*/,
                            new AsyncResult(message.obj, null,
                                    new SatelliteManager.SatelliteException(
                                            SatelliteManager.SATELLITE_RESULT_SERVICE_ERROR)))
                    .sendToTarget();
            return null;
        }).when(mMockSatelliteModemInterface).sendSatelliteDatagram(any(SatelliteDatagram.class),
                anyBoolean(), anyBoolean(), any(Message.class));

        mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, DATAGRAM_TYPE2, mDatagram,
                true, mResultListener::offer);

        processAllMessages();

        mInOrder.verify(mMockDatagramController)
                .needsWaitingForSatelliteConnected(eq(DATAGRAM_TYPE2));
        mInOrder.verify(mMockDatagramController).isPollingInIdleState();
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID), eq(DATAGRAM_TYPE2),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING), eq(1),
                        eq(SATELLITE_RESULT_SUCCESS));
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID), eq(DATAGRAM_TYPE2),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED), eq(0),
                        eq(SatelliteManager.SATELLITE_RESULT_SERVICE_ERROR));
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID), eq(DATAGRAM_TYPE2),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE), eq(0),
                        eq(SATELLITE_RESULT_SUCCESS));
        verifyNoMoreInteractions(mMockDatagramController);

        assertThat(mResultListener.peek()).isEqualTo(
                SatelliteManager.SATELLITE_RESULT_SERVICE_ERROR);
        verify(mMockSessionMetricsStats, times(1))
                .addCountOfFailedOutgoingDatagram(anyInt(), anyInt());
    }

    @Test
    public void testSendSatelliteDatagram_DemoMode_Align_Success() throws Exception {
        doAnswer(invocation -> {
            Message message = (Message) invocation.getArguments()[3];
            mDatagramDispatcherUT.obtainMessage(2 /*EVENT_SEND_SATELLITE_DATAGRAM_DONE*/,
                            new AsyncResult(message.obj, null, null))
                    .sendToTarget();
            return null;
        }).when(mMockSatelliteModemInterface).sendSatelliteDatagram(any(SatelliteDatagram.class),
                anyBoolean(), anyBoolean(), any(Message.class));
        mDatagramDispatcherUT.setDemoMode(true);
        mDatagramDispatcherUT.setDeviceAlignedWithSatellite(true);

        int[] sosDatagramTypes = {DATAGRAM_TYPE1, DATAGRAM_TYPE4, DATAGRAM_TYPE5};
        for (int datagramType : sosDatagramTypes) {
            clearInvocations(mMockDatagramController);
            clearInvocations(mMockSessionMetricsStats);
            mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, datagramType, mDatagram,
                    true, mResultListener::offer);

            processAllMessages();
            moveTimeForward(TIMEOUT_DATAGRAM_DELAY_IN_DEMO_MODE);
            processAllMessages();

            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(eq(SUB_ID), eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING), eq(1),
                            eq(SATELLITE_RESULT_SUCCESS));
            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(eq(SUB_ID), eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS),
                            eq(0),
                            eq(SATELLITE_RESULT_SUCCESS));
            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(eq(SUB_ID), eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE), eq(0),
                            eq(SATELLITE_RESULT_SUCCESS));
            assertThat(mResultListener.peek()).isEqualTo(SATELLITE_RESULT_SUCCESS);
            verify(mMockSessionMetricsStats, times(1))
                    .addCountOfSuccessfulOutgoingDatagram(eq(datagramType));
            mDatagramDispatcherUT.setDemoMode(false);
            mDatagramDispatcherUT.setDeviceAlignedWithSatellite(false);
        }
    }

    @Test
    public void testSendSatelliteDatagram_DemoMode_Align_failed() throws Exception {
        doAnswer(invocation -> {
            Message message = (Message) invocation.getArguments()[3];
            mDatagramDispatcherUT.obtainMessage(2 /*EVENT_SEND_SATELLITE_DATAGRAM_DONE*/,
                            new AsyncResult(message.obj, null, null))
                    .sendToTarget();
            return null;
        }).when(mMockSatelliteModemInterface).sendSatelliteDatagram(any(SatelliteDatagram.class),
                anyBoolean(), anyBoolean(), any(Message.class));

        long previousTimer = mDatagramDispatcherUT.getSatelliteAlignedTimeoutDuration();
        mDatagramDispatcherUT.setDemoMode(true);
        mDatagramDispatcherUT.setDuration(TEST_EXPIRE_TIMER_SATELLITE_ALIGN);
        mDatagramDispatcherUT.setDeviceAlignedWithSatellite(false);
        when(mMockDatagramController.waitForAligningToSatellite(false)).thenReturn(true);

        int[] sosDatagramTypes = {DATAGRAM_TYPE1, DATAGRAM_TYPE4, DATAGRAM_TYPE5};
        for (int datagramType : sosDatagramTypes) {
            clearInvocations(mMockDatagramController);
            clearInvocations(mMockSessionMetricsStats);
            mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, datagramType, mDatagram,
                    true, mResultListener::offer);

            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(eq(SUB_ID), eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING), eq(1),
                            eq(SATELLITE_RESULT_SUCCESS));
            processAllFutureMessages();
            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(eq(SUB_ID), eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED),
                            anyInt(), eq(SatelliteManager.SATELLITE_RESULT_NOT_REACHABLE));
            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(eq(SUB_ID), eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE), eq(0),
                            eq(SATELLITE_RESULT_SUCCESS));
            assertThat(mResultListener.peek()).isEqualTo(
                    SatelliteManager.SATELLITE_RESULT_NOT_REACHABLE);
            verify(mMockDatagramController, never()).pollPendingSatelliteDatagrams(anyInt(), any());
            verify(mMockDatagramController, never()).pushDemoModeDatagram(
                    anyInt(), any(SatelliteDatagram.class));
            verify(mMockSessionMetricsStats, times(1))
                    .addCountOfFailedOutgoingDatagram(anyInt(), anyInt());
        }

        mDatagramDispatcherUT.setDemoMode(false);
        mDatagramDispatcherUT.setDeviceAlignedWithSatellite(false);
        mDatagramDispatcherUT.setDuration(previousTimer);
    }

    @Test
    public void testSendSatelliteDatagram_DemoMode_data_type_location_sharing() throws Exception {
        doAnswer(invocation -> {
            Message message = (Message) invocation.getArguments()[3];
            mDatagramDispatcherUT.obtainMessage(2 /*EVENT_SEND_SATELLITE_DATAGRAM_DONE*/,
                    new AsyncResult(message.obj, SATELLITE_RESULT_SUCCESS,
                            null)).sendToTarget();
            return null;
        }).when(mMockSatelliteModemInterface).sendSatelliteDatagram(any(SatelliteDatagram.class),
                anyBoolean(), anyBoolean(), any(Message.class));
        mDatagramDispatcherUT.setDemoMode(true);
        mDatagramDispatcherUT.setDeviceAlignedWithSatellite(true);
        replaceInstance(PhoneFactory.class, "sPhones", null, new Phone[] {mPhone});

        mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, DATAGRAM_TYPE2, mDatagram,
                true, mResultListener::offer);

        processAllMessages();
        moveTimeForward(TIMEOUT_DATAGRAM_DELAY_IN_DEMO_MODE);
        processAllMessages();

        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID), eq(DATAGRAM_TYPE2),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING), eq(1),
                        eq(SATELLITE_RESULT_SUCCESS));

        assertThat(mResultListener.peek()).isEqualTo(SATELLITE_RESULT_SUCCESS);

        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID), eq(DATAGRAM_TYPE2),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS), eq(0),
                        eq(SATELLITE_RESULT_SUCCESS));

        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID), eq(DATAGRAM_TYPE2),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE), eq(0),
                        eq(SATELLITE_RESULT_SUCCESS));
        verify(mMockSessionMetricsStats, times(1))
                .addCountOfSuccessfulOutgoingDatagram(eq(DATAGRAM_TYPE2));

        mDatagramDispatcherUT.setDemoMode(false);
        mDatagramDispatcherUT.setDeviceAlignedWithSatellite(false);
    }

    @Test
    public void testSatelliteModemBusy_modemPollingDatagram_sendingDelayed() {
        when(mMockDatagramController.isPollingInIdleState()).thenReturn(false);

        int[] sosDatagramTypes = {DATAGRAM_TYPE1, DATAGRAM_TYPE4, DATAGRAM_TYPE5};
        for (int datagramType : sosDatagramTypes) {
            clearInvocations(mMockDatagramController);
            mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, datagramType, mDatagram,
                    true, mResultListener::offer);
            processAllMessages();
            // As modem is busy receiving datagrams, sending datagram did not proceed further.
            mInOrder.verify(mMockDatagramController)
                    .needsWaitingForSatelliteConnected(eq(datagramType));
            mInOrder.verify(mMockDatagramController, times(2)).isPollingInIdleState();
            verifyNoMoreInteractions(mMockDatagramController);
        }
    }

    @Test
    public void testOnSatelliteModemStateChanged_modemStateListening() {
        mDatagramDispatcherUT.onSatelliteModemStateChanged(
                SatelliteManager.SATELLITE_MODEM_STATE_LISTENING);
        processAllMessages();
        verifyNoMoreInteractions(mMockDatagramController);
    }

    @Test
    public void testOnSatelliteModemStateChanged_modemStateOff_modemSendingDatagrams() {
        int[] sosDatagramTypes = {DATAGRAM_TYPE1, DATAGRAM_TYPE4, DATAGRAM_TYPE5};
        for (int datagramType : sosDatagramTypes) {
            clearInvocations(mMockDatagramController);
            mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, datagramType, mDatagram,
                    true, mResultListener::offer);

            mDatagramDispatcherUT.onSatelliteModemStateChanged(
                    SatelliteManager.SATELLITE_MODEM_STATE_OFF);

            processAllMessages();

            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(anyInt(), eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED),
                            eq(1), eq(SatelliteManager.SATELLITE_RESULT_REQUEST_ABORTED));
            mInOrder.verify(mMockDatagramController)
                    .updateSendStatus(anyInt(), eq(datagramType),
                            eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE),
                            eq(0), eq(SATELLITE_RESULT_SUCCESS));
        }
    }

    @Test
    public void testOnSatelliteModemStateChanged_modemStateOff_modemNotSendingDatagrams() {
        mDatagramDispatcherUT.onSatelliteModemStateChanged(
                SatelliteManager.SATELLITE_MODEM_STATE_OFF);

        processAllMessages();

        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(anyInt(), anyInt(),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE),
                        eq(0), eq(SATELLITE_RESULT_SUCCESS));
    }

    @Test
    public void testSendSatelliteDatagramToModemInDemoMode() throws Exception {
        when(mFeatureFlags.oemEnabledSatelliteFlag()).thenReturn(true);

        mDatagramDispatcherUT.setDemoMode(true);
        mDatagramDispatcherUT.setDeviceAlignedWithSatellite(true);

        int[] sosDatagramTypes = {DATAGRAM_TYPE1, DATAGRAM_TYPE4, DATAGRAM_TYPE5};
        for (int datagramType : sosDatagramTypes) {
            mIntegerConsumerSemaphore.drainPermits();
            mIntegerConsumerResult.clear();
            clearInvocations(mMockDatagramController);
            clearInvocations(mMockSatelliteModemInterface);
            clearInvocations(mMockSessionMetricsStats);
            doAnswer(invocation -> {
                Message message = (Message) invocation.getArguments()[3];
                mDatagramDispatcherUT.obtainMessage(2 /*EVENT_SEND_SATELLITE_DATAGRAM_DONE*/,
                                new AsyncResult(message.obj, null, null))
                        .sendToTarget();
                return null;
            }).when(mMockSatelliteModemInterface).sendSatelliteDatagram(
                    any(SatelliteDatagram.class),
                    anyBoolean(), anyBoolean(), any(Message.class));

            // Test when overlay config config_send_satellite_datagram_to_modem_in_demo_mode is true
            mDatagramDispatcherUT.setShouldSendDatagramToModemInDemoMode(null);
            mContextFixture.putBooleanResource(mConfigSendSatelliteDatagramToModemInDemoMode, true);
            mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, datagramType, mDatagram,
                    true, mIntegerConsumer);
            processAllMessages();
            moveTimeForward(TIMEOUT_DATAGRAM_DELAY_IN_DEMO_MODE);
            processAllMessages();
            waitForIntegerConsumerResult(1);
            assertEquals(SATELLITE_RESULT_SUCCESS, (int) mIntegerConsumerResult.get(0));
            mIntegerConsumerResult.clear();
            verify(mMockSatelliteModemInterface, times(1)).sendSatelliteDatagram(
                    any(SatelliteDatagram.class), anyBoolean(), anyBoolean(), any(Message.class));

            moveTimeForward(TIMEOUT_DATAGRAM_DELAY_IN_DEMO_MODE);
            processAllMessages();
            verify(mMockDatagramController).pushDemoModeDatagram(
                    anyInt(), any(SatelliteDatagram.class));
            verify(mMockDatagramController).pollPendingSatelliteDatagrams(anyInt(), any());
            verify(mMockSessionMetricsStats, times(1))
                    .addCountOfSuccessfulOutgoingDatagram(anyInt());

            // Test when overlay config config_send_satellite_datagram_to_modem_in_demo_mode is
            // false
            reset(mMockSatelliteModemInterface);
            mDatagramDispatcherUT.setShouldSendDatagramToModemInDemoMode(null);
            mContextFixture.putBooleanResource(mConfigSendSatelliteDatagramToModemInDemoMode,
                    false);
            mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, datagramType, mDatagram,
                    true, mIntegerConsumer);
            processAllMessages();
            moveTimeForward(TIMEOUT_DATAGRAM_DELAY_IN_DEMO_MODE);
            processAllMessages();

            waitForIntegerConsumerResult(1);
            assertEquals(SATELLITE_RESULT_SUCCESS, (int) mIntegerConsumerResult.get(0));
            mIntegerConsumerResult.clear();
            verify(mMockSatelliteModemInterface, never()).sendSatelliteDatagram(
                    any(SatelliteDatagram.class), anyBoolean(), anyBoolean(), any(Message.class));

            moveTimeForward(TIMEOUT_DATAGRAM_DELAY_IN_DEMO_MODE);
            processAllMessages();
            verify(mMockDatagramController, times(2)).pushDemoModeDatagram(
                    anyInt(), any(SatelliteDatagram.class));
            verify(mMockDatagramController, times(2)).pollPendingSatelliteDatagrams(anyInt(),
                    any());

            // Send datagram one more time
            reset(mMockSatelliteModemInterface);
            mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, datagramType, mDatagram,
                    true, mIntegerConsumer);
            processAllMessages();
            moveTimeForward(TIMEOUT_DATAGRAM_DELAY_IN_DEMO_MODE);
            processAllMessages();

            waitForIntegerConsumerResult(1);
            assertEquals(SATELLITE_RESULT_SUCCESS, (int) mIntegerConsumerResult.get(0));
            mIntegerConsumerResult.clear();
            verify(mMockSatelliteModemInterface, never()).sendSatelliteDatagram(
                    any(SatelliteDatagram.class), anyBoolean(), anyBoolean(), any(Message.class));

            moveTimeForward(TIMEOUT_DATAGRAM_DELAY_IN_DEMO_MODE);
            processAllMessages();
            verify(mMockDatagramController, times(3)).pushDemoModeDatagram(
                    anyInt(), any(SatelliteDatagram.class));
            verify(mMockDatagramController, times(3)).pollPendingSatelliteDatagrams(anyInt(),
                    any());
        }

        mDatagramDispatcherUT.setDemoMode(false);
        mDatagramDispatcherUT.setDeviceAlignedWithSatellite(false);
        mDatagramDispatcherUT.setShouldSendDatagramToModemInDemoMode(null);
    }

    private boolean waitForIntegerConsumerResult(int expectedNumberOfEvents) {
        for (int i = 0; i < expectedNumberOfEvents; i++) {
            try {
                if (!mIntegerConsumerSemaphore.tryAcquire(TIMEOUT, TimeUnit.MILLISECONDS)) {
                    loge("Timeout to receive IIntegerConsumer() callback");
                    return false;
                }
            } catch (Exception ex) {
                loge("waitForIIntegerConsumerResult: Got exception=" + ex);
                return false;
            }
        }
        return true;
    }

    private static class TestDatagramDispatcher extends DatagramDispatcher {
        private long mLong = SATELLITE_ALIGN_TIMEOUT;

        TestDatagramDispatcher(@NonNull Context context, @NonNull Looper looper,
                @NonNull FeatureFlags featureFlags,
                @NonNull DatagramController datagramController) {
            super(context, looper, featureFlags, datagramController);
        }

        @Override
        protected void setDemoMode(boolean isDemoMode) {
            super.setDemoMode(isDemoMode);
        }

        @Override
        public void setDeviceAlignedWithSatellite(boolean isAligned) {
            super.setDeviceAlignedWithSatellite(isAligned);
        }

        @Override
        protected long getSatelliteAlignedTimeoutDuration() {
            return mLong;
        }

        @Override
        protected void setShouldSendDatagramToModemInDemoMode(
                @Nullable Boolean shouldSendToModemInDemoMode) {
            super.setShouldSendDatagramToModemInDemoMode(shouldSendToModemInDemoMode);
        }

        public void setDuration(long duration) {
            mLong = duration;
        }
    }

    private static void loge(String message) {
        Rlog.e(TAG, message);
    }
}
