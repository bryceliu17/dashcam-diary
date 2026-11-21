package com.example.dashcam.recording

import android.content.Context

internal enum class RecordingMode(val label: String) {
    Frontend("Frontend Recording"),
    PowerAuto("Power Auto Background"),
    VolumeVideoDoublePress("Volume Up Double-Press Video"),
    VolumeAudioDoublePress("Volume Up Double-Press Audio"),
    PowerDiary("Power-linked diary (audio / video)");

    val allowsRemoteRecordingCommands: Boolean
        get() = this != PowerAuto && this != PowerDiary
}

internal object RecordingModeSettings {
    const val ACTION_REMOTE_MODE_CHANGED = "com.example.dashcam.recording.REMOTE_MODE_CHANGED"

    fun current(context: Context): RecordingMode = when {
        PowerDiaryService.isSelected(context) -> RecordingMode.PowerDiary
        PowerRecordingSettings.isPowerAutoBackgroundEnabled(context) -> RecordingMode.PowerAuto
        PowerRecordingSettings.isVolumeKeyStartEnabled(context) -> RecordingMode.VolumeVideoDoublePress
        PowerRecordingSettings.isVolumeKeyAudioStartEnabled(context) -> RecordingMode.VolumeAudioDoublePress
        else -> RecordingMode.Frontend
    }

    // Power Auto starts its monitor separately; merely selecting Power Diary never starts it.
    fun select(context: Context, mode: RecordingMode, remotePowerConnected: Boolean? = null) {
        PowerDiaryService.setSelected(context, mode == RecordingMode.PowerDiary)
        PowerRecordingSettings.setPowerAutoBackgroundEnabled(context, mode == RecordingMode.PowerAuto)
        PowerRecordingSettings.setVolumeKeyStartEnabled(context, mode == RecordingMode.VolumeVideoDoublePress)
        PowerRecordingSettings.setVolumeKeyAudioStartEnabled(context, mode == RecordingMode.VolumeAudioDoublePress)
        if (mode != RecordingMode.PowerAuto) {
            PowerMonitorService.stop(context)
        } else if (remotePowerConnected != null) {
            // A remote mode selection must not use a power connection already in progress.
            PowerRecordingSettings.setLastKnownChargingState(context, remotePowerConnected)
            PowerRecordingSettings.setPowerAutoStartSuppressed(context, remotePowerConnected)
        }
    }
}
