package com.example.dashcam.recording

import android.Manifest
import android.app.NotificationChannel
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
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

class PowerMonitorService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastChargingState: Boolean? = null
    private var monitoring = false
    private val disconnectGrace = PowerAutoDisconnectGrace()
    private var disconnectWakeLock: PowerManager.WakeLock? = null
    private val disconnectStopRunnable = Runnable { stopIfStillDisconnected() }
    private val batteryPollRunnable = object : Runnable {
        override fun run() {
            pollPowerState()
            if (monitoring) mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!PowerRecordingSettings.isPowerAutoBackgroundEnabled(this@PowerMonitorService)) return
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> handlePowerConnected(newConnection = lastChargingState != true)
                Intent.ACTION_POWER_DISCONNECTED -> handlePowerDisconnected()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ContextCompat.registerReceiver(this, powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !PowerRecordingSettings.isPowerAutoBackgroundEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        startMonitoring()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handlePowerConnected(newConnection: Boolean) {
        cancelDisconnectStop()
        lastChargingState = true
        if (PowerRecordingSettings.lastKnownChargingState(this) != true) {
            PowerRecordingSettings.setLastKnownChargingState(this, true)
        }
        if (newConnection) PowerRecordingSettings.setPowerAutoStartSuppressed(this, false)
        if (PowerRecordingSettings.isPowerAutoStartSuppressed(this)) return
        if (PowerRecordingSettings.isAnyRecordingActive(this) || LiveAccessSettings.isStreaming(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            Log.i(TAG, "Power connected while monitoring; starting background recording")
            ContextCompat.startForegroundService(
                this,
                Intent(this, BackgroundRecordingService::class.java)
                    .setAction(BackgroundRecordingService.ACTION_START_ON_POWER)
            )
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to start background recording from power monitor", error)
        }
    }

    private fun handlePowerDisconnected() {
        lastChargingState = false
        if (PowerRecordingSettings.lastKnownChargingState(this) != false) {
            PowerRecordingSettings.setLastKnownChargingState(this, false)
        }
        if (!PowerRecordingSettings.isBackgroundRecordingActive(this)) return
        val now = SystemClock.elapsedRealtime()
        if (disconnectGrace.remainingMs(now) != null) return
        disconnectGrace.onDisconnected(now)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        disconnectWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:PowerAutoStop").apply {
            setReferenceCounted(false)
            acquire(POWER_DISCONNECT_GRACE_MS + 2_000L)
        }
        mainHandler.postDelayed(disconnectStopRunnable, POWER_DISCONNECT_GRACE_MS)
    }

    private fun stopIfStillDisconnected() {
        val remaining = disconnectGrace.remainingMs(SystemClock.elapsedRealtime()) ?: return
        if (remaining > 0) {
            mainHandler.postDelayed(disconnectStopRunnable, remaining)
            return
        }
        try {
            if (!PowerRecordingSettings.isPowerAutoBackgroundEnabled(this)) return
            if (isPowerConnected(this) != false || !PowerRecordingSettings.isBackgroundRecordingActive(this)) return
            Log.i(TAG, "Power disconnected for $POWER_DISCONNECT_GRACE_SECONDS seconds; stopping and saving background video")
            startService(
                Intent(this, BackgroundRecordingService::class.java)
                    .setAction(BackgroundRecordingService.ACTION_STOP_ON_POWER_LOSS)
            )
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to stop background video after power loss", error)
        } finally {
            cancelDisconnectStop()
        }
    }

    private fun cancelDisconnectStop() {
        disconnectGrace.cancel()
        mainHandler.removeCallbacks(disconnectStopRunnable)
        disconnectWakeLock?.let { if (it.isHeld) it.release() }
        disconnectWakeLock = null
    }

    private fun startMonitoring() {
        lastChargingState = PowerRecordingSettings.lastKnownChargingState(this)
        if (monitoring) {
            pollPowerState()
            return
        }
        if (!isBackgroundRecordingServiceRunning()) {
            PowerRecordingSettings.setBackgroundRecordingActive(this, false)
        }
        monitoring = true
        pollPowerState()
        // Recover the stop timer if the monitor was restarted with a recording still active.
        if (lastChargingState == false) handlePowerDisconnected()
        mainHandler.postDelayed(batteryPollRunnable, POLL_INTERVAL_MS)
        Log.i(TAG, "Power monitor polling started, charging=$lastChargingState")
    }

    private fun pollPowerState() {
        if (!PowerRecordingSettings.isPowerAutoBackgroundEnabled(this)) return
        val charging = isPowerConnected(this) ?: return
        val previous = lastChargingState
        if (charging) {
            val newConnection = previous == false
            if (newConnection) Log.i(TAG, "Power connected detected by polling")
            if (!isBackgroundRecordingServiceRunning() &&
                PowerRecordingSettings.isBackgroundRecordingActive(this)
            ) {
                Log.w(TAG, "Clearing stale background recording state")
                PowerRecordingSettings.setBackgroundRecordingActive(this, false)
            }
            handlePowerConnected(newConnection)
        } else if (previous == true) {
            Log.i(TAG, "Power disconnected detected by polling")
            handlePowerDisconnected()
        } else if (previous == null) {
            handlePowerDisconnected()
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_dashcam)
        .setContentTitle("Dashcam Diary power monitor")
        .setContentText("Waiting for power connection")
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            pendingIntentFlags()
        ))
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Dashcam Diary power monitor", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun isBackgroundRecordingServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == BackgroundRecordingService::class.java.name
        }
    }

    override fun onDestroy() {
        monitoring = false
        mainHandler.removeCallbacks(batteryPollRunnable)
        cancelDisconnectStop()
        try { unregisterReceiver(powerReceiver) } catch (_: IllegalArgumentException) { }
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "com.example.dashcam.power_monitor.START"
        const val ACTION_STOP = "com.example.dashcam.power_monitor.STOP"
        private const val CHANNEL_ID = "dashcam_power_monitor"
        private const val NOTIFICATION_ID = 2002
        private const val POLL_INTERVAL_MS = 10_000L
        private const val TAG = "PowerMonitorService"

        internal fun isPowerConnected(context: Context): Boolean? =
            context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, PowerMonitorService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, PowerMonitorService::class.java)
            )
        }
    }
}
