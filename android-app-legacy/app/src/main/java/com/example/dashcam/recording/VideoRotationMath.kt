package com.example.dashcam.recording

/** All returned angles are clockwise, relative to the player's file-oriented picture. */
internal object VideoRotationMath {
    fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360

    fun quantize(orientation: Int, previous: Int?): Int {
        val angle = normalize(orientation)
        if (previous != null) {
            val distance = normalize(angle - previous).let { minOf(it, 360 - it) }
            if (distance < 50) return previous
        }
        return normalize(((angle + 45) / 90) * 90)
    }

    // OrientationEventListener runs clockwise; Surface rotations run counter-clockwise.
    fun surfaceRotation(deviceOrientation: Int): Int = normalize(-deviceOrientation) / 90

    fun extra(desiredClockwise: Int, fileOrientedClockwise: Int): Int =
        normalize(desiredClockwise - fileOrientedClockwise)

    fun forRecorder(sensorOrientation: Int, frontFacing: Boolean, deviceOrientation: Int, fileHint: Int): Int {
        val desired = sensorOrientation + if (frontFacing) -deviceOrientation else deviceOrientation
        return extra(desired, fileHint)
    }
}
