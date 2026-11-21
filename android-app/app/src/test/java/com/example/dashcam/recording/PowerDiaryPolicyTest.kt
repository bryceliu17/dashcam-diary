package com.example.dashcam.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerDiaryPolicyTest {
    @Test fun startsAudioOnBattery() {
        assertFalse(PowerDiaryPolicy.videoWanted(false, false, null, 100))
    }

    @Test fun startsVideoWhenAlreadyPlugged() {
        assertTrue(PowerDiaryPolicy.videoWanted(true, false, null, 100))
    }

    @Test fun keepsVideoUntilFifteenSecondsAfterUnplugging() {
        assertTrue(PowerDiaryPolicy.videoWanted(false, true, 100, 15_099))
        assertFalse(PowerDiaryPolicy.videoWanted(false, true, 100, 15_100))
    }

    @Test fun restoredPowerKeepsVideo() {
        assertTrue(PowerDiaryPolicy.videoWanted(true, true, 100, 15_099))
    }

    @Test fun unplugGraceOnlyAppliesToVideo() {
        assertFalse(PowerDiaryPolicy.videoWanted(false, false, 100, 200))
    }
}
