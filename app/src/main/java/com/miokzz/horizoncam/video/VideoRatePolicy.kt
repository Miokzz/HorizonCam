package com.miokzz.horizoncam.video

/** A camera/session declaration, not evidence that a saved file reached this cadence. */
data class VideoFpsRange(val lower: Int, val upper: Int) {
    init {
        require(lower > 0 && upper >= lower)
    }
}

enum class VideoQualityPreset {
    FHD,
    UHD,
    HD,
    SD,
}

data class VideoModeRequest(
    val quality: VideoQualityPreset,
    val fps: Int,
    val nativeEis: Boolean = false,
)

object VideoRatePolicy {
    /** Prefer a fixed cadence. Never claim 60 from a range whose ceiling is 30. */
    fun selectRange(ranges: Collection<VideoFpsRange>, requestedFps: Int): VideoFpsRange? {
        require(requestedFps == 30 || requestedFps == 60)
        return ranges
            .filter { it.upper == requestedFps && it.lower <= requestedFps }
            .maxByOrNull { it.lower }
    }

    /** Missing declarations allow only an explicitly unverified default-30 session. */
    fun negotiateRange(ranges: Collection<VideoFpsRange>, requestedFps: Int): VideoFpsRange? {
        val selected = selectRange(ranges, requestedFps)
        require(requestedFps != 60 || selected != null) {
            "60 fps requer declaração específica da sessão."
        }
        return selected
    }

    fun frameRates(ranges: Collection<VideoFpsRange>): Set<Int> =
        setOf(30, 60).filter { selectRange(ranges, it) != null }.toSet()

    fun nativeEisAllowed(quality: VideoQualityPreset, fps: Int, declared: Boolean) =
        declared && quality != VideoQualityPreset.UHD && fps == 30

    /** Preserve selected resolution before reducing it; every change is explicit to the UI. */
    fun fallbacks(
        request: VideoModeRequest,
        supported: Map<VideoQualityPreset, Set<Int>>,
        eisDeclared: Boolean,
    ): List<VideoModeRequest> {
        val candidates =
            listOf(
                request,
                request.copy(nativeEis = false),
                request.copy(fps = 30, nativeEis = false),
                VideoModeRequest(VideoQualityPreset.FHD, 30, false),
                VideoModeRequest(VideoQualityPreset.HD, 30, false),
                VideoModeRequest(VideoQualityPreset.SD, 30, false),
            )
        return candidates.distinct().filter { candidate ->
            candidate.fps in supported[candidate.quality].orEmpty() &&
                (!candidate.nativeEis ||
                    nativeEisAllowed(candidate.quality, candidate.fps, eisDeclared))
        }
    }
}
