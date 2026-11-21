package com.example.dashcam.live

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyLiveCameraHandoffTest {
    @Test fun idleCameraStartsWithoutFixedDelay() {
        assertEquals(0L, LegacyLiveCameraHandoff().initialDelayMs(5_000, switchingCamera = false))
    }

    @Test fun visiblePreviewAndCameraSwitchKeepFullCooldown() {
        val handoff = LegacyLiveCameraHandoff()
        assertEquals(900L, handoff.initialDelayMs(5_000, switchingCamera = true))
        handoff.previewHostStarted()
        assertEquals(900L, handoff.initialDelayMs(5_000, switchingCamera = false))
    }

    @Test fun recentPreviewOrRecorderReleaseWaitsOutCooldown() {
        val handoff = LegacyLiveCameraHandoff()
        handoff.previewHostStarted()
        handoff.previewHostStopped(1_000)
        assertEquals(700L, handoff.initialDelayMs(1_200, switchingCamera = false))
        handoff.cameraReleased(1_500)
        handoff.cameraReleased(1_400) // A late report must not shorten a newer release window.
        assertEquals(200L, handoff.remainingReleaseDelayMs(2_200))
        assertEquals(1L, handoff.initialDelayMs(2_399, switchingCamera = false))
        assertEquals(0L, handoff.initialDelayMs(2_400, switchingCamera = false))
    }
}
