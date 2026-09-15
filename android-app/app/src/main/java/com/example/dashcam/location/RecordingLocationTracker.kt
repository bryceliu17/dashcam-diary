package com.example.dashcam.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.dashcam.data.LocationPointEntity

class RecordingLocationTracker(private val context: Context) : LocationListener {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val lock = Any()
    private var recordingUuid: String? = null
    private var mediaType = ""
    private var mode = GpsRecordingMode.Off
    private val points = mutableListOf<LocationPointEntity>()

    fun start(uuid: String, type: String, selectedMode: GpsRecordingMode) {
        cancel()
        if (selectedMode == GpsRecordingMode.Off || !hasPermission()) return
        synchronized(lock) {
            recordingUuid = uuid
            mediaType = type
            mode = selectedMode
            points.clear()
        }
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { manager.isProviderEnabled(it) }
            if (providers.isEmpty()) {
                cancel()
                return
            }
            providers.mapNotNull(manager::getLastKnownLocation)
                .filter { System.currentTimeMillis() - it.time in 0..120_000L }
                .maxByOrNull { it.time }
                ?.let(::record)
            providers.forEach { provider ->
                manager.requestLocationUpdates(
                    provider,
                    selectedMode.minimumIntervalMs,
                    selectedMode.minimumDistanceMeters,
                    this,
                    Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {
            cancel()
        } catch (_: IllegalArgumentException) {
            cancel()
        }
    }

    fun finish(): List<LocationPointEntity> {
        try { manager.removeUpdates(this) } catch (_: SecurityException) { }
        return synchronized(lock) {
            val result = points.toList()
            recordingUuid = null
            mediaType = ""
            mode = GpsRecordingMode.Off
            points.clear()
            result
        }
    }

    fun cancel() { finish() }

    override fun onLocationChanged(location: Location) = record(location)
    @Suppress("DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun record(location: Location) {
        synchronized(lock) {
            val uuid = recordingUuid ?: return
            if (mode == GpsRecordingMode.Off) return
            val timestamp = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
            val previous = points.lastOrNull()
            if (previous != null && timestamp <= previous.recordedAt) return
            points += LocationPointEntity(
                recordingUuid = uuid,
                mediaType = mediaType,
                recordedAt = timestamp,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy,
                speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
                bearingDegrees = location.bearing.takeIf { location.hasBearing() },
                altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
                provider = location.provider
            )
        }
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
