package com.example.dashcam

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Playback position is an offset from the recording's database start time, not the current clock. */
internal object PlaybackRecordingTime {
    fun format(startTimeMs: Long?, positionMs: Int, timeZone: TimeZone = TimeZone.getDefault()): String {
        if (startTimeMs == null) return "--"
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            this.timeZone = timeZone
        }
        return formatter.format(Date(startTimeMs + positionMs.coerceAtLeast(0).toLong()))
    }
}
