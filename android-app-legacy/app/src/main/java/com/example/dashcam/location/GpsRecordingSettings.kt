package com.example.dashcam.location

import android.content.Context

enum class GpsRecordingMode(
    val label: String,
    val minimumIntervalMs: Long,
    val minimumDistanceMeters: Float
) {
    Off("Off", 0L, 0f),
    Dashcam("Dashcam · 3 sec / 10 m", 3_000L, 10f),
    Bodycam("Bodycam · 5 sec / 5 m", 5_000L, 5f),
    AudioDiary("Audio diary · 60 sec / 100 m", 60_000L, 100f),
    BatterySaver("Battery saver · 15 sec / 25 m", 15_000L, 25f)
}

object GpsRecordingSettings {
    private const val PREFS = "dashcam_settings"
    private const val KEY_VIDEO_MODE = "video_gps_recording_mode"
    private const val KEY_AUDIO_MODE = "audio_gps_recording_mode"

    fun videoMode(context: Context): GpsRecordingMode = read(context, KEY_VIDEO_MODE)
    fun audioMode(context: Context): GpsRecordingMode = read(context, KEY_AUDIO_MODE)

    fun setVideoMode(context: Context, mode: GpsRecordingMode) = write(context, KEY_VIDEO_MODE, mode)
    fun setAudioMode(context: Context, mode: GpsRecordingMode) = write(context, KEY_AUDIO_MODE, mode)

    private fun read(context: Context, key: String): GpsRecordingMode {
        val stored = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, GpsRecordingMode.Off.name)
        return GpsRecordingMode.entries.firstOrNull { it.name == stored } ?: GpsRecordingMode.Off
    }

    private fun write(context: Context, key: String, mode: GpsRecordingMode) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, mode.name).apply()
    }
}
