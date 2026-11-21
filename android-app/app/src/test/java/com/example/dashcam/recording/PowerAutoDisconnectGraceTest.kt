package com.example.dashcam.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerAutoDisconnectGraceTest {
    @Test fun disconnectWaitsFifteenSeconds() {
        val grace = PowerAutoDisconnectGrace()
        assertNull(grace.remainingMs(0))
        grace.onDisconnected(1_000)
        assertEquals(15_000L, grace.remainingMs(1_000))
        assertEquals(1L, grace.remainingMs(15_999))
        assertEquals(0L, grace.remainingMs(16_000))
    }

    @Test fun repeatDisconnectDoesNotExtendDeadline() {
        val grace = PowerAutoDisconnectGrace()
        grace.onDisconnected(1_000)
        grace.onDisconnected(9_000)
        assertEquals(0L, grace.remainingMs(16_000))
    }

    @Test fun reconnectionCancelsStopAndNextDisconnectGetsNewDeadline() {
        val grace = PowerAutoDisconnectGrace()
        grace.onDisconnected(1_000)
        grace.cancel()
        assertNull(grace.remainingMs(16_000))
        grace.onDisconnected(17_000)
        assertEquals(15_000L, grace.remainingMs(17_000))
        assertEquals(0L, grace.remainingMs(32_000))
    }
}
