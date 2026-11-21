package com.example.dashcam.live

/** Only skip the Android 5 camera cooldown when no app camera is likely to be closing. */
internal class LegacyLiveCameraHandoff {
    private var previewHosts = 0
    private var lastReleaseAtMs: Long? = null

    @Synchronized fun previewHostStarted() {
        previewHosts++
    }

    @Synchronized fun previewHostStopped(nowMs: Long) {
        previewHosts = (previewHosts - 1).coerceAtLeast(0)
        cameraReleased(nowMs)
    }

    @Synchronized fun cameraReleased(nowMs: Long) {
        val previous = lastReleaseAtMs
        if (previous == null || nowMs > previous) lastReleaseAtMs = nowMs
    }

    @Synchronized fun initialDelayMs(nowMs: Long, switchingCamera: Boolean): Long {
        if (switchingCamera || previewHosts > 0) return RELEASE_DELAY_MS
        return remainingReleaseDelayMs(nowMs)
    }

    @Synchronized fun remainingReleaseDelayMs(nowMs: Long): Long {
        val lastRelease = lastReleaseAtMs ?: return 0L
        return (RELEASE_DELAY_MS - (nowMs - lastRelease)).coerceIn(0L, RELEASE_DELAY_MS)
    }

    companion object {
        const val RELEASE_DELAY_MS = 900L
        val shared = LegacyLiveCameraHandoff()
    }
}
