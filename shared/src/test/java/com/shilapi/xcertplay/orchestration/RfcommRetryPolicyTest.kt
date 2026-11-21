package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PATCH-0133：复用档 RFCOMM 早停判据。
 *
 * 只测纯函数，不碰 Bluetooth / 车机读数。判据原文见
 * [shouldStopRfcommRetry] 的 KDoc。
 */
class RfcommRetryPolicyTest {

    @Test
    fun `hotspot on with zero clients stops after first round`() {
        assertTrue(shouldStopRfcommRetry(round = 1, clientCount = 0, apEnabled = true))
    }

    @Test
    fun `hotspot on with one client keeps retrying`() {
        assertFalse(shouldStopRfcommRetry(round = 1, clientCount = 1, apEnabled = true))
    }

    @Test
    fun `hotspot off with zero clients keeps retrying`() {
        assertFalse(shouldStopRfcommRetry(round = 1, clientCount = 0, apEnabled = false))
    }

    @Test
    fun `unknown client count keeps the original retry behaviour`() {
        assertFalse(shouldStopRfcommRetry(round = 1, clientCount = null, apEnabled = true))
    }
}
