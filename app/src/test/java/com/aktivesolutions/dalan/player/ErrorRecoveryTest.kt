package com.aktivesolutions.dalan.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorRecoveryTest {
    @Test fun sourceErrorSwitchesClient() {
        val r = ErrorRecovery(3)
        r.reset("v")
        val a = r.onFailure(FailureKind.SOURCE, "Response code: 403") as Recovery.Retry
        assertTrue(a.switchClient)
    }

    @Test fun givesUpAfterThreeAttempts() {
        val r = ErrorRecovery(3)
        r.reset("v")
        repeat(3) { assertTrue(r.onFailure(FailureKind.SOURCE, "x") is Recovery.Retry) }
        assertTrue(r.onFailure(FailureKind.SOURCE, "boom") is Recovery.GiveUp)
    }

    @Test fun readyBlipDoesNotResetAttempts() {
        val r = ErrorRecovery(3)
        r.reset("v")
        repeat(2) { r.onFailure(FailureKind.SOURCE, "x", positionMs = 40_000) }
        // plays 3 s after the failure, then fails again: still 2 attempts used
        for (t in 0..3) r.onTick(true, 40_000L + t * 1000)
        assertEquals(2, r.attempts)
    }

    @Test fun resetsAfter15sOfContinuousPlaybackPastFailure() {
        val r = ErrorRecovery(3)
        r.reset("v")
        repeat(2) { r.onFailure(FailureKind.SOURCE, "x", positionMs = 40_000) }
        for (t in 0..14) r.onTick(true, 40_000L + t * 1000)
        assertEquals(2, r.attempts)
        r.onTick(true, 55_000)
        assertEquals(0, r.attempts)
    }

    @Test fun pauseBreaksTheStreak() {
        val r = ErrorRecovery(3)
        r.reset("v")
        r.onFailure(FailureKind.SOURCE, "x", positionMs = 0)
        for (t in 0..10) r.onTick(true, t * 1000L)
        r.onTick(false, 10_000)
        for (t in 0..10) r.onTick(true, 10_000L + t * 1000)
        assertEquals(1, r.attempts)
        r.onTick(true, 25_000)
        assertEquals(0, r.attempts)
    }

    @Test fun sabrReloadCarriesToken() {
        val r = ErrorRecovery(3)
        r.reset("v")
        val a = r.onFailure(FailureKind.SABR_RELOAD, "reload", "TOKEN") as Recovery.Retry
        assertEquals("TOKEN", a.reloadToken)
        assertEquals(false, a.switchClient)
    }

    @Test fun rendererErrorLowersQuality() {
        val r = ErrorRecovery(3)
        r.reset("v")
        assertTrue((r.onFailure(FailureKind.VIDEO_RENDERER, "x") as Recovery.Retry).lowerQuality)
        assertTrue((r.onFailure(FailureKind.OUT_OF_MEMORY, "x") as Recovery.Retry).lowerQuality)
    }

    @Test fun resetForNewVideoClearsAttempts() {
        val r = ErrorRecovery(3)
        r.reset("a"); repeat(3) { r.onFailure(FailureKind.SOURCE, "x") }
        r.reset("b")
        assertTrue(r.onFailure(FailureKind.SOURCE, "x") is Recovery.Retry)
    }
}
