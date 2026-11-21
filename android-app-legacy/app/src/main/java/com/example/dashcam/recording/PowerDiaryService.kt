package com.example.dashcam.recording

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.dashcam.MainActivity
import com.example.dashcam.R
import com.example.dashcam.live.LiveAccessSettings

/** Owns the two recorders; never starts the next until the previous service has saved and exited. */
class PowerDiaryService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var unpluggedAt: Long? = null
    private var transitionStarted = 0L
    private var releaseUntil = 0L
    private var startedTarget: Boolean? = null
    private var pendingStopVideo: Boolean? = null
    private var stopping = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val tick = object : Runnable {
        override fun run() {
            try { reconcile() } catch (error: Exception) {
                Log.e("PowerDiary", "Mode failed", error)
                stopMode("Mode stopped: ${error.message}")
            }
            if (active) handler.postDelayed(this,
                if (transitionStarted == 0L && unpluggedAt == null && !stopping) 5_000L else 500L)
        }
    }
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!active || stopping) return
            val connected = when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> true
                Intent.ACTION_POWER_DISCONNECTED -> false
                else -> return
            }
            if (connected) unpluggedAt = null
            else if (unpluggedAt == null) {
                unpluggedAt = SystemClock.elapsedRealtime()
                holdTransitionWakeLock()
            }
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Power-linked diary", NotificationManager.IMPORTANCE_LOW))
        }
        ContextCompat.registerReceiver(this, powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(2010, notification())
        if (intent?.action == STOP) {
            stopMode("Saving current recording; stopping mode")
        } else if (!active) {
            if (!isSelected(this)) { stopSelf(); return START_NOT_STICKY }
            val permitted = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
            if (!permitted || LiveAccessSettings.isStreaming(this) ||
                (intent != null && PowerRecordingSettings.isAnyRecordingActive(this))) {
                status = "Stop other recording and grant camera/microphone permission first"
                stopSelf()
                return START_NOT_STICKY
            }
            active = true
            stopping = false
            startedTarget = null
            if (intent == null) {
                // A sticky service restart must not inherit dead recorders' preference flags.
                if (!running(BackgroundRecordingService::class.java)) PowerRecordingSettings.setBackgroundRecordingActive(this, false)
                if (!running(AudioRecordingService::class.java)) PowerRecordingSettings.setAudioRecordingActive(this, false)
            }
            holdTransitionWakeLock()
            status = "Starting power-linked diary"
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return if (stopping) START_NOT_STICKY else START_STICKY
    }

    private fun reconcile() {
        if (!active) { stopSelf(); return }
        val now = SystemClock.elapsedRealtime()
        val videoRunning = running(BackgroundRecordingService::class.java)
        val audioRunning = running(AudioRecordingService::class.java)
        if (stopping) {
            if (!videoRunning && !audioRunning) { active = false; stopSelf() }
            return
        }
        if (!isSelected(this)) { stopMode("Mode stopped"); return }
        val plugged = isPlugged(this)
        if (plugged) unpluggedAt = null
        else if (startedTarget == true && unpluggedAt == null) {
            unpluggedAt = now
            holdTransitionWakeLock()
        }
        val videoWanted = PowerDiaryPolicy.videoWanted(plugged, startedTarget == true, unpluggedAt, now)
        if (!plugged && videoWanted) {
            val secondsLeft = (POWER_DISCONNECT_GRACE_MS - (now - (unpluggedAt ?: now)) + 999) / 1_000
            status = "Power disconnected: switching to audio in ${secondsLeft.coerceAtLeast(0)}s"
            return
        }
        val opposite = if (videoWanted) audioRunning else videoRunning
        if (opposite) {
            if (transitionStarted == 0L) {
                transitionStarted = now
                startedTarget = null
                pendingStopVideo = !videoWanted
                holdTransitionWakeLock()
                sendRecorder(!videoWanted, false)
                status = "Saving ${if (videoWanted) "audio" else "video"} before switching"
            }
            if (now - transitionStarted > 60_000L) stopMode("Mode stopped: previous recording did not finish saving")
            releaseUntil = now + 700L
            return
        }
        val targetRunning = if (videoWanted) videoRunning else audioRunning
        pendingStopVideo?.let { stoppedVideo ->
            val error = if (stoppedVideo) RemoteRecordingControl.recordingError else RemoteRecordingControl.audioRecordingError
            if (error != null) { stopMode("Mode stopped while saving: $error"); return }
            pendingStopVideo = null
        }
        if (startedTarget == videoWanted) {
            if (!targetRunning) {
                stopMode("Mode stopped: recorder exited. Check storage and recording permissions.")
                return
            }
            val started = if (videoWanted) RemoteRecordingControl.recorderStarted else RemoteRecordingControl.audioRecorderStarted
            if (started) {
                if (!videoWanted) unpluggedAt = null
                status = if (videoWanted) "Mode running: background video (power connected)" else "Mode running: audio (battery)"
                transitionStarted = 0L
                releaseTransitionWakeLock()
            } else if (!targetRunning || now - transitionStarted > 30_000L) {
                val error = if (videoWanted) RemoteRecordingControl.recordingError else RemoteRecordingControl.audioRecordingError
                stopMode("Mode stopped: ${error ?: "recording failed to start"}")
            }
            return
        }
        if (targetRunning || now < releaseUntil) return
        transitionStarted = now
        startedTarget = videoWanted
        holdTransitionWakeLock()
        if (videoWanted) {
            RemoteRecordingControl.recorderStarted = false
            RemoteRecordingControl.recordingError = null
        } else {
            RemoteRecordingControl.audioRecorderStarted = false
            RemoteRecordingControl.audioRecordingError = null
        }
        sendRecorder(videoWanted, true)
        status = "Starting ${if (videoWanted) "background video" else "audio"}"
    }

    private fun stopMode(message: String) {
        if (stopping) return
        stopping = true
        status = message
        holdTransitionWakeLock()
        // Do not deliver a second STOP while the old service is asynchronously saving.
        if (pendingStopVideo != true && running(BackgroundRecordingService::class.java)) sendRecorder(true, false)
        if (pendingStopVideo != false && running(AudioRecordingService::class.java)) sendRecorder(false, false)
        if (!active) stopSelf()
    }

    private fun sendRecorder(video: Boolean, start: Boolean) {
        val intent = if (video) Intent(this, BackgroundRecordingService::class.java).setAction(
            if (start) BackgroundRecordingService.ACTION_START else BackgroundRecordingService.ACTION_STOP)
        else Intent(this, AudioRecordingService::class.java).setAction(
            if (start) AudioRecordingService.ACTION_START else AudioRecordingService.ACTION_STOP)
        intent.putExtra(MANAGED, true)
        if (start) ContextCompat.startForegroundService(this, intent) else startService(intent)
    }

    @Suppress("DEPRECATION")
    private fun running(type: Class<*>): Boolean =
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == type.name }

    private fun holdTransitionWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dashcam:power-diary-transition").apply { acquire(90_000L) }
    }
    private fun releaseTransitionWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_dashcam).setContentTitle("Power-linked diary active")
        .setContentText("Power: video · Battery: audio · Open app to stop")
        .setOngoing(true).setContentIntent(PendingIntent.getActivity(this, 2010,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)).build()

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(powerReceiver)
        active = false
        releaseTransitionWakeLock()
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "dashcam_power_diary"
        private const val START = "com.example.dashcam.power_diary.START"
        private const val STOP = "com.example.dashcam.power_diary.STOP"
        private const val MANAGED = "power_diary_managed"
        @Volatile var active = false
            private set
        @Volatile var status = "Mode ready: press Start mode or double-press Volume Up"
            private set
        fun isSelected(context: Context) = context.getSharedPreferences("dashcam_settings", Context.MODE_PRIVATE)
            .getBoolean("power_diary_selected", false)
        fun setSelected(context: Context, selected: Boolean) {
            context.getSharedPreferences("dashcam_settings", Context.MODE_PRIVATE).edit()
                .putBoolean("power_diary_selected", selected).apply()
        }
        fun isManaged(intent: Intent?) = intent?.getBooleanExtra(MANAGED, false) == true
        fun start(context: Context) {
            if (!active) ContextCompat.startForegroundService(context, Intent(context, PowerDiaryService::class.java).setAction(START))
        }
        fun stop(context: Context) = context.startService(Intent(context, PowerDiaryService::class.java).setAction(STOP))
        fun isPlugged(context: Context): Boolean = (context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra("plugged", 0) ?: 0) != 0
    }
}

internal object PowerDiaryPolicy {
    fun videoWanted(plugged: Boolean, wasVideo: Boolean, unpluggedAt: Long?, now: Long): Boolean =
        plugged || (wasVideo && unpluggedAt != null && now - unpluggedAt < POWER_DISCONNECT_GRACE_MS)
}
