package com.example.dashcam.recording

/** A continuous run owns one extra playback rotation, including a no-reading fallback. */
internal class RecordingRunRotation {
    @Volatile var isCaptured = false
        private set
    private var degrees: Int? = null

    @Synchronized fun reset() {
        degrees = null
        isCaptured = false
    }

    @Synchronized fun getOrCapture(calculate: () -> Int?): Int? {
        if (!isCaptured) {
            degrees = calculate()
            isCaptured = true
        }
        return degrees
    }
}
