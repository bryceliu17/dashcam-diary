package com.example.dashcam

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRecordingTimeTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun start(value: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = utc }.parse(value)!!.time

    @Test fun playbackPositionIsAddedToTheRecordingStart() {
        assertEquals("2026-10-27 15:30:20",
            PlaybackRecordingTime.format(start("2026-10-27 15:30:00"), 20_000, utc))
    }

    @Test fun seekingAcrossMidnightUpdatesTheDate() {
        assertEquals("2026-10-28 00:00:10",
            PlaybackRecordingTime.format(start("2026-10-27 23:59:50"), 20_000, utc))
    }

    @Test fun recordedClockUsesTheRequestedLocalTimeZone() {
        assertEquals("2026-10-28 01:30:20",
            PlaybackRecordingTime.format(start("2026-10-27 15:30:00"), 20_000,
                TimeZone.getTimeZone("Australia/Brisbane")))
    }

    @Test fun negativePositionCannotDisplayBeforeTheRecordingStarted() {
        assertEquals("2026-10-27 15:30:00",
            PlaybackRecordingTime.format(start("2026-10-27 15:30:00"), -500, utc))
    }

    @Test fun anUnknownStartDoesNotInventAClockTime() {
        assertEquals("--", PlaybackRecordingTime.format(null, 20_000, utc))
    }

    @Test fun switchingRecordingsUsesTheNewStartTime() {
        assertEquals("2026-10-27 15:30:05",
            PlaybackRecordingTime.format(start("2026-10-27 15:30:00"), 5_000, utc))
        assertEquals("2026-10-28 09:00:05",
            PlaybackRecordingTime.format(start("2026-10-28 09:00:00"), 5_000, utc))
    }
}
