package com.example.dashcam.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoRotationMathTest {
    @Test fun automaticSegmentsReuseTheFirstRotationEvenIfThePhoneMoves() {
        val run = RecordingRunRotation()
        var captures = 0
        assertEquals(270, run.getOrCapture { captures++; 270 })
        repeat(10) {
            assertEquals(270, run.getOrCapture { captures++; 90 })
        }
        assertEquals(1, captures)
    }

    @Test fun restartingAfterManualOrPowerStopCapturesANewDirection() {
        val run = RecordingRunRotation()
        assertEquals(90, run.getOrCapture { 90 })
        run.reset()
        assertEquals(180, run.getOrCapture { 180 })
        run.reset()
        assertEquals(270, run.getOrCapture { 270 })
    }

    @Test fun zeroRotationIsAValidFrozenValue() {
        val run = RecordingRunRotation()
        assertEquals(0, run.getOrCapture { 0 })
        assertEquals(0, run.getOrCapture { 270 })
    }

    @Test fun missingFirstReadingDoesNotChooseADifferentRotationMidRun() {
        val run = RecordingRunRotation()
        assertEquals(null, run.getOrCapture { null })
        assertEquals(null, run.getOrCapture { 90 })
        run.reset()
        assertEquals(90, run.getOrCapture { 90 })
    }

    @Test fun postureUsesTheOppositeSurfaceConvention() {
        assertEquals(0, VideoRotationMath.surfaceRotation(0))
        assertEquals(3, VideoRotationMath.surfaceRotation(90))
        assertEquals(2, VideoRotationMath.surfaceRotation(180))
        assertEquals(1, VideoRotationMath.surfaceRotation(270))
    }

    @Test fun backCameraWithItsNormalFileHintNeedsOnlyPostureCorrection() {
        for (sensor in listOf(0, 90, 180, 270)) {
            for (posture in listOf(0, 90, 180, 270)) {
                assertEquals(posture, VideoRotationMath.forRecorder(sensor, false, posture, sensor))
            }
        }
    }

    @Test fun frontCameraUsesTheOppositePostureCorrection() {
        for (sensor in listOf(0, 90, 180, 270)) {
            for (posture in listOf(0, 90, 180, 270)) {
                assertEquals(VideoRotationMath.normalize(-posture),
                    VideoRotationMath.forRecorder(sensor, true, posture, sensor))
            }
        }
    }

    @Test fun arbitraryExistingFileHintsAreCompensatedWithoutChangingThem() {
        for (sensor in listOf(90, 270)) {
            for (front in listOf(false, true)) {
                for (hint in listOf(0, 90, 180, 270)) {
                    for (posture in listOf(0, 90, 180, 270)) {
                        val correction = VideoRotationMath.forRecorder(sensor, front, posture, hint)
                        val expected = VideoRotationMath.normalize(sensor + if (front) -posture else posture)
                        assertEquals(expected, VideoRotationMath.normalize(hint + correction))
                    }
                }
            }
        }
    }

    @Test fun cameraXAlreadyOrientedForTheCurrentPostureNeedsNoExtraRotation() {
        for (posture in listOf(0, 90, 180, 270)) {
            val desired = VideoRotationMath.normalize(90 + posture)
            assertEquals(0, VideoRotationMath.extra(desired, desired))
        }
        assertEquals(270, VideoRotationMath.extra(0, 90))
        assertEquals(90, VideoRotationMath.extra(90, 0))
    }

    @Test fun hysteresisPreventsBoundaryJitterAndWrapsAtUpright() {
        assertEquals(90, VideoRotationMath.quantize(46, null))
        assertEquals(0, VideoRotationMath.quantize(49, 0))
        assertEquals(90, VideoRotationMath.quantize(50, 0))
        assertEquals(90, VideoRotationMath.quantize(136, 90))
        assertEquals(180, VideoRotationMath.quantize(140, 90))
        assertEquals(0, VideoRotationMath.quantize(359, 0))
        assertEquals(0, VideoRotationMath.quantize(350, null))
    }
}
