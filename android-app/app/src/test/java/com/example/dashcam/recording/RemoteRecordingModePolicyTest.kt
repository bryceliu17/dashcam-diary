package com.example.dashcam.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteRecordingModePolicyTest {
    @Test fun onlyAutomaticModesRejectManualRemoteRecording() {
        assertEquals(setOf(RecordingMode.PowerAuto, RecordingMode.PowerDiary),
            RecordingMode.entries.filterNot { it.allowsRemoteRecordingCommands }.toSet())
    }
}
