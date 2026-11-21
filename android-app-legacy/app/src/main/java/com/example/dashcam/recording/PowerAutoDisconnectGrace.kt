package com.example.dashcam.recording

internal const val POWER_DISCONNECT_GRACE_SECONDS = 15
internal const val POWER_DISCONNECT_GRACE_MS = POWER_DISCONNECT_GRACE_SECONDS * 1_000L

internal class PowerAutoDisconnectGrace {
    private var deadlineMs: Long? = null

    fun onDisconnected(nowMs: Long) {
        if (deadlineMs == null) deadlineMs = nowMs + POWER_DISCONNECT_GRACE_MS
    }

    fun cancel() {
        deadlineMs = null
    }

    fun remainingMs(nowMs: Long): Long? =
        deadlineMs?.let { (it - nowMs).coerceAtLeast(0L) }
}
