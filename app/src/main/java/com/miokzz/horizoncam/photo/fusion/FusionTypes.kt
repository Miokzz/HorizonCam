package com.miokzz.horizoncam.photo.fusion

/** Upright, same-field SDR RGB pixels. The caller retains ownership of every input array. */
data class FusionFrame(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val exposureTimeNs: Long? = null,
    val iso: Int? = null,
)

data class FusionOptions(
    val superResolution: Boolean = false,
    val maxShiftPixels: Int = 16,
    val maxFrames: Int = 8,
    val maxLongEdge: Int = 1280,
    val srMaxLongEdge: Int = 960,
)

data class FusionProgress(
    val completedFrames: Int,
    val totalFrames: Int,
    val stage: String,
    val completedRows: Int = 0,
    val totalRows: Int = 0,
)

/** Candidate at (referenceX + dx, referenceY + dy) samples the registered reference field. */
data class FrameAlignment(
    val frameIndex: Int,
    val dx: Double,
    val dy: Double,
    val confidence: Double,
    val exposureGain: Double,
    val sharpnessRatio: Double,
    val accepted: Boolean,
    val reason: String? = null,
    val subpixelReliable: Boolean = false,
    val relativeExposure: Double? = null,
    val motionRejectedFraction: Float = 0f,
)

data class FusionResult(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val referenceIndex: Int,
    val acceptedFrameCount: Int,
    val alignments: List<FrameAlignment>,
    val superResolved: Boolean,
    val phaseCoverage: Float,
    val measuredSampleFraction: Float = 0f,
    val highlightRecoveredPixels: Int = 0,
)
