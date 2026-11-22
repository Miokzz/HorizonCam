package com.miokzz.horizoncam

import com.miokzz.horizoncam.video.*
import kotlin.math.roundToLong
import org.junit.Assert.*
import org.junit.Test

class VideoPolicyTest {
    @Test
    fun qualitySpecificEvidenceNeverPromotesFhd60IntoUhd60() {
        val fhd = listOf(VideoFpsRange(30, 30), VideoFpsRange(30, 60), VideoFpsRange(60, 60))
        val uhd = listOf(VideoFpsRange(15, 30), VideoFpsRange(30, 30))
        assertEquals(setOf(30, 60), VideoRatePolicy.frameRates(fhd))
        assertEquals(setOf(30), VideoRatePolicy.frameRates(uhd))
        assertNull(VideoRatePolicy.selectRange(uhd, 60))
        assertEquals(VideoFpsRange(60, 60), VideoRatePolicy.selectRange(fhd, 60))
        assertEquals(setOf(30, 60), VideoRatePolicy.frameRates(uhd + VideoFpsRange(60, 60)))
    }

    @Test
    fun variableRangeIsPreservedAndUnverifiedFallbackIsLimitedToThirty() {
        assertEquals(
            VideoFpsRange(30, 60),
            VideoRatePolicy.negotiateRange(listOf(VideoFpsRange(30, 60)), 60),
        )
        assertEquals(
            VideoFpsRange(15, 30),
            VideoRatePolicy.negotiateRange(listOf(VideoFpsRange(15, 30)), 30),
        )
        assertNull(VideoRatePolicy.negotiateRange(emptyList(), 30))
        try {
            VideoRatePolicy.negotiateRange(emptyList(), 60)
            fail("Unverified 60 accepted")
        } catch (_: IllegalArgumentException) {}
        // A high-speed ceiling does not establish this ordinary 60fps mode.
        assertFalse(60 in VideoRatePolicy.frameRates(listOf(VideoFpsRange(30, 120))))
    }

    @Test
    fun fallbackPreservesResolutionBeforeFpsAndShowsHdSdAsExplicitChoices() {
        val supported =
            mapOf(
                VideoQualityPreset.UHD to setOf(30),
                VideoQualityPreset.FHD to setOf(30, 60),
                VideoQualityPreset.HD to setOf(30),
                VideoQualityPreset.SD to setOf(30),
            )
        assertEquals(
            listOf(
                VideoModeRequest(VideoQualityPreset.UHD, 30),
                VideoModeRequest(VideoQualityPreset.FHD, 30),
                VideoModeRequest(VideoQualityPreset.HD, 30),
                VideoModeRequest(VideoQualityPreset.SD, 30),
            ),
            VideoRatePolicy.fallbacks(VideoModeRequest(VideoQualityPreset.UHD, 60), supported, true),
        )
        val emulator = mapOf(VideoQualityPreset.HD to setOf(30))
        assertEquals(
            listOf(VideoModeRequest(VideoQualityPreset.HD, 30)),
            VideoRatePolicy.fallbacks(VideoModeRequest(VideoQualityPreset.FHD, 30), emulator, false),
        )
    }

    @Test
    fun allFourDeclaredModesCanAttemptEisBeforeFallingBackAtTheSameResolutionAndRate() {
        for (quality in listOf(VideoQualityPreset.FHD, VideoQualityPreset.UHD)) {
            for (fps in listOf(30, 60)) {
                val request = VideoModeRequest(quality, fps, true)
                val modes = VideoRatePolicy.fallbacks(request, mapOf(quality to setOf(30, 60)), true)
                assertEquals(request, modes[0])
                assertEquals(request.copy(nativeEis = false), modes[1])
            }
        }
        val fallback =
            VideoRatePolicy.fallbacks(
                VideoModeRequest(VideoQualityPreset.FHD, 30, true),
                mapOf(VideoQualityPreset.FHD to setOf(30)),
                false,
            )
        assertEquals(listOf(VideoModeRequest(VideoQualityPreset.FHD, 30, false)), fallback)
    }

    @Test
    fun measuredFileCadenceDetectsThirtyDespiteSixtyRequestAndHandlesBframes() {
        val thirty = VideoCadence.measure((0..300).map { (it * 1_000_000.0 / 30).roundToLong() })
        assertEquals(30.0, thirty.averageFps!!, 0.001)
        val sixty = VideoCadence.measure((0..600).map { (it * 1_000_000.0 / 60).roundToLong() })
        assertEquals(60.0, sixty.averageFps!!, 0.001)
        val reordered = VideoCadence.measure(listOf(0L, 66_667L, 33_333L, 100_000L))
        assertEquals(30.0, reordered.averageFps!!, 0.001)
        assertEquals(4, reordered.distinctPresentationTimes)
    }

    @Test
    fun cadenceReportsDuplicatesGapsEmptyEvidenceAndBoundedWindowHonestly() {
        val one = VideoCadence.measure(listOf(-1L, 0L, 0L))
        assertNull(one.averageFps)
        assertEquals(1, one.duplicatePresentationTimes)
        val dropped = VideoCadence.measure(listOf(0L, 16_667L, 33_333L, 100_000L), true)
        assertEquals(30.0, dropped.averageFps!!, 0.001)
        assertEquals(66_667L, dropped.longestIntervalUs)
        assertTrue(dropped.sampleLimitReached)
        assertNull(VideoCadence.measure(emptyList()).averageFps)
    }
}
