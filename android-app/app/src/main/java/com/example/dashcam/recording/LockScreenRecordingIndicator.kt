package com.example.dashcam.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.dashcam.R

/** A status-only lock-screen notification. Recording controls stay behind the keyguard. */
object LockScreenRecordingIndicator {
    private const val PREFS = "dashcam_settings"
    private const val KEY_ENABLED = "show_recording_on_lock_screen"
    private const val CHANNEL_ID = "dashcam_lock_screen_recording"
    private const val VIDEO_ID = 3001
    private const val AUDIO_ID = 3002

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        sync(context)
    }

    fun sync(context: Context) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!isEnabled(appContext)) {
            manager.cancel(VIDEO_ID)
            manager.cancel(AUDIO_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Recording on lock screen", NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        setShowBadge(false)
                    }
            )
        }
        update(manager, appContext, VIDEO_ID, PowerRecordingSettings.isVideoRecordingActive(appContext),
            "Recording video")
        update(manager, appContext, AUDIO_ID, PowerRecordingSettings.isAudioRecordingActive(appContext),
            "Recording audio")
    }

    private fun update(manager: NotificationManager, context: Context, id: Int, active: Boolean, text: String) {
        if (!active) {
            manager.cancel(id)
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dashcam)
            .setContentTitle("Dashcam Diary")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        // Deliberately no action or content intent: tapping cannot control recording while locked.
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Notification permission may be disabled; recording must continue unaffected.
        }
    }
}
