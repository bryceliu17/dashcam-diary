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
import android.content.pm.ServiceInfo
import android.os.BatteryManager
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

/** Keeps camera/mic foreground eligibility while handing off between the existing recorders. */
class PowerDiaryService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private var recorder: Recorder? = null
    private var pendingStop = false
    private var recorderStarted = false
    private var stopping = false
    private var serviceReady = false
    private var unpluggedAt: Long? = null
    private var transitionStartedAt = 0L
    private var releaseUntil = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private enum class Recorder(val type: Class<out Service>, val start: String, val stop: String, val label: String) {
        Audio(AudioRecordingService::class.java, AudioRecordingService.ACTION_START, AudioRecordingService.ACTION_STOP, "audio"),
        Video(BackgroundRecordingService::class.java, BackgroundRecordingService.ACTION_START, BackgroundRecordingService.ACTION_STOP, "background video");

        fun started() = if (this == Video) RemoteRecordingControl.recorderStarted else RemoteRecordingControl.audioRecorderStarted
        fun error() = if (this == Video) RemoteRecordingControl.recordingError else RemoteRecordingControl.audioRecordingError
        fun resetStatus() {
            if (this == Video) {
                RemoteRecordingControl.recorderStarted = false
                RemoteRecordingControl.recordingError = null
            } else {
                RemoteRecordingControl.audioRecorderStarted = false
                RemoteRecordingControl.audioRecordingError = null
            }
        }
    }

    private val tick = Runnable {
        try {
            reconcile()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Power-linked diary failed", error)
            stopMode("Mode stopped: ${error.message ?: "recording error"}")
        }
        if (active) scheduleTick(if (stopping || pendingStop || !recorderStarted || unpluggedAt != null) 250L else 5_000L)
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!active || stopping) return
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> unpluggedAt = null
                Intent.ACTION_POWER_DISCONNECTED -> if (recorder == Recorder.Video && unpluggedAt == null) {
                    unpluggedAt = SystemClock.elapsedRealtime()
                }
            }
            holdTransitionWakeLock()
            scheduleTick(0L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Power-linked diary", NotificationManager.IMPORTANCE_LOW)
        )
        ContextCompat.registerReceiver(this, powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            if (active) stopMode("Saving and stopping power-linked diary") else stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY
        if (intent?.action != START || !isSelected(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val anyRecorderRunning = Recorder.entries.any(::isRunning)
            check(hasPermissions(this)) { "Grant camera and microphone permissions first" }
            check(!LiveAccessSettings.isStreaming(this) && !anyRecorderRunning) { "Stop other recording or live camera first" }
            // A dead process can leave saved activity flags that would block the next recorder.
            PowerRecordingSettings.setBackgroundRecordingActive(this, false)
            PowerRecordingSettings.setAudioRecordingActive(this, false)
            PowerRecordingSettings.setForegroundRecordingActive(this, false)
            promoteToForeground()
            active = true
            releaseUntil = SystemClock.elapsedRealtime() + CAMERA_RELEASE_MS
            holdTransitionWakeLock()
            scheduleTick(CAMERA_RELEASE_MS)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to start mode", error)
            status = "Unable to start mode. Open the app, check permissions, and try again."
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        status = "Starting power-linked diary"
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            if (listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
                    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                }) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        // ServiceCompat masks out types unavailable on older Android versions.
        androidx.core.app.ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), types)
        serviceReady = true
    }

    private fun reconcile() {
        val now = SystemClock.elapsedRealtime()
        val current = recorder
        if (pendingStop && current != null) {
            if (isRunning(current)) {
                if (now - transitionStartedAt >= 60_000L) {
                    stopping = true
                    updateStatus("Still waiting for ${current.label} to finish saving")
                }
                return
            }
            pendingStop = false
            recorder = null
            recorderStarted = false
            releaseUntil = now + CAMERA_RELEASE_MS
            if (current.error() != null) {
                endMode("Mode stopped while saving: ${current.error()}")
                return
            }
            if (stopping) endMode(READY)
            return
        }
        if (stopping || !isSelected(this)) {
            stopMode("Saving and stopping power-linked diary")
            return
        }

        val plugged = (registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        if (plugged || current != Recorder.Video) unpluggedAt = null
        else if (unpluggedAt == null) {
            unpluggedAt = now
            holdTransitionWakeLock()
        }
        val wanted = if (PowerDiaryPolicy.videoWanted(plugged, current == Recorder.Video, unpluggedAt, now))
            Recorder.Video else Recorder.Audio
        if (current == null) {
            if (now < releaseUntil) return
            currentStart(wanted, now)
            return
        }
        if (current.error() != null || (!isRunning(current) && (recorderStarted || now - transitionStartedAt >= 2_000L))) {
            stopMode("Mode stopped: ${current.error() ?: "${current.label} recorder exited"}")
            return
        }
        if (!recorderStarted) {
            recorderStarted = current.started()
            if (!recorderStarted) {
                if (now - transitionStartedAt >= 30_000L) stopMode("Mode stopped: ${current.label} did not start")
                return
            }
        }
        if (wanted != current) {
            requestRecorderStop(current)
        } else if (unpluggedAt != null) {
            updateStatus("Power disconnected: switching to audio after $POWER_DISCONNECT_GRACE_SECONDS seconds")
        } else {
            updateStatus(if (current == Recorder.Video) "Mode running: background video" else "Mode running: audio")
            releaseTransitionWakeLock()
        }
    }

    private fun currentStart(wanted: Recorder, now: Long) {
        holdTransitionWakeLock()
        wanted.resetStatus()
        recorder = wanted
        recorderStarted = false
        transitionStartedAt = now
        updateStatus("Starting ${wanted.label}")
        // The controller remains the visible, typed foreground service across the handoff.
        send(wanted, wanted.start)
    }

    private fun stopMode(message: String) {
        stopping = true
        val current = recorder
        if (current == null || !isRunning(current)) {
            endMode(if (message.startsWith("Mode stopped")) message else READY)
        } else if (!pendingStop) {
            requestRecorderStop(current)
            updateStatus(message)
        }
        if (active) scheduleTick(250L)
    }

    private fun requestRecorderStop(current: Recorder) {
        holdTransitionWakeLock()
        send(current, current.stop)
        pendingStop = true
        transitionStartedAt = SystemClock.elapsedRealtime()
        updateStatus("Saving ${current.label}")
    }

    private fun endMode(message: String) {
        active = false
        status = message
        stopSelf()
    }

    private fun send(current: Recorder, action: String) {
        checkNotNull(startService(Intent(this, current.type).setAction(action).putExtra(MANAGED, true))) {
            "Could not reach ${current.label} service"
        }
    }

    @Suppress("DEPRECATION")
    private fun isRunning(current: Recorder): Boolean =
        getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == current.type.name }

    private fun scheduleTick(delay: Long) {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delay)
    }

    private fun holdTransitionWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dashcam:power-diary-transition").apply { acquire(90_000L) }
    }

    private fun releaseTransitionWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun updateStatus(message: String) {
        if (status == message) return
        status = message
        if (serviceReady) notifications.notify(NOTIFICATION_ID, notification())
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_dashcam)
        .setContentTitle("Power-linked diary")
        .setContentText(status)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, NOTIFICATION_ID, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(powerReceiver)
        active = false
        releaseTransitionWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "dashcam_power_diary"
        private const val TAG = "PowerDiary"
        private const val NOTIFICATION_ID = 2010
        private const val CAMERA_RELEASE_MS = 700L
        private const val START = "com.example.dashcam.power_diary.START"
        private const val STOP = "com.example.dashcam.power_diary.STOP"
        private const val MANAGED = "power_diary_managed"
        private const val READY = "Ready: start here or double-press Volume Up"
        val permissions = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        var active = false
            private set
        var status = READY
            private set

        fun hasPermissions(context: Context) =
            permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

        private fun prefs(context: Context) = context.getSharedPreferences("dashcam_settings", Context.MODE_PRIVATE)
        fun isSelected(context: Context) = prefs(context).getBoolean("power_diary_selected", false)
        fun setSelected(context: Context, selected: Boolean) {
            prefs(context).edit().putBoolean("power_diary_selected", selected).apply()
            if (selected && !active) status = READY
        }
        fun isManaged(intent: Intent?) = intent?.getBooleanExtra(MANAGED, false) == true

        fun start(context: Context) {
            if (!active && isSelected(context)) ContextCompat.startForegroundService(
                context, Intent(context, PowerDiaryService::class.java).setAction(START)
            )
        }
        fun stop(context: Context) {
            if (active) context.startService(Intent(context, PowerDiaryService::class.java).setAction(STOP))
        }
    }
}

internal object PowerDiaryPolicy {
    fun videoWanted(plugged: Boolean, wasVideo: Boolean, unpluggedAt: Long?, now: Long): Boolean =
        plugged || (wasVideo && unpluggedAt != null && now - unpluggedAt < POWER_DISCONNECT_GRACE_MS)
}
