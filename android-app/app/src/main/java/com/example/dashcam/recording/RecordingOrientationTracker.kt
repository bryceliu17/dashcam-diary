package com.example.dashcam.recording

import android.content.Context
import android.hardware.SensorManager
import android.util.Log
import android.view.OrientationEventListener
import androidx.camera.core.CameraInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Freeze the first segment's database correction until this continuous recording stops. */
class RecordingOrientationTracker(context: Context) {
    @Volatile private var orientation: Int? = null
    @Volatile private var freshSample = false
    private var enabled = false
    private val runRotation = RecordingRunRotation()
    private val listener = object : OrientationEventListener(context.applicationContext, SensorManager.SENSOR_DELAY_NORMAL) {
        override fun onOrientationChanged(degrees: Int) {
            if (!enabled || runRotation.isCaptured || degrees == ORIENTATION_UNKNOWN) return
            orientation = VideoRotationMath.quantize(degrees, orientation)
            freshSample = true
        }
    }

    fun start() {
        if (enabled) return
        runRotation.reset()
        orientation = null
        freshSample = false
        enabled = true
        if (listener.canDetectOrientation()) listener.enable()
    }

    /** Called on the main dispatcher before recording, so a cold start can receive a sensor event. */
    suspend fun awaitReady() {
        start()
        if (enabled && !runRotation.isCaptured && !freshSample && listener.canDetectOrientation()) {
            withTimeoutOrNull(600) {
                while (!freshSample) delay(25)
            }
        }
    }

    fun stop() {
        enabled = false
        listener.disable()
    }

    fun cameraXExtraRotation(cameraInfo: CameraInfo, targetRotation: Int): Int? = freezeRotation { device ->
        val desired = cameraInfo.getSensorRotationDegrees(VideoRotationMath.surfaceRotation(device))
        val baseline = cameraInfo.getSensorRotationDegrees(targetRotation)
        VideoRotationMath.extra(desired, baseline).also {
            Log.i(TAG, "CameraX recording run: posture=$device target=$targetRotation extra=$it")
        }
    }

    fun recorderExtraRotation(sensorOrientation: Int, frontFacing: Boolean, fileHint: Int): Int? = freezeRotation { device ->
        VideoRotationMath.forRecorder(sensorOrientation, frontFacing, device, fileHint).also {
            Log.i(TAG, "Recorder recording run: posture=$device sensor=$sensorOrientation front=$frontFacing hint=$fileHint extra=$it")
        }
    }

    private fun freezeRotation(calculate: (Int) -> Int): Int? = runRotation.getOrCapture {
        // Even a missing reading is frozen: later segments must not choose a new direction.
        val device = orientation
        listener.disable()
        device?.let(calculate)
    }

    private companion object {
        const val TAG = "RecordingOrientation"
    }
}
