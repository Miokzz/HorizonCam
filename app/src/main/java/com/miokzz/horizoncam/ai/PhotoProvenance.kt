package com.miokzz.horizoncam.ai

internal object PhotoProvenance {
    enum class Kind { NEURAL, FUSION, NATIVE, CAMERA }

    fun describe(
        model: String?,
        frames: Int,
        zoom: Float,
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): String {
        require(frames >= 1)
        val process = when {
            model != null -> "Horizon AI 2 | $model | textura estimada"
            frames > 1 -> "HorizonCam | detalhe multiframe | sem restauracao neural"
            else -> "HorizonCam | detalhe nativo | sem restauracao neural"
        }
        return "$process | zoom ${zoom}x | frames=$frames | " +
            "input/output ${sourceWidth}x${sourceHeight} -> ${outputWidth}x${outputHeight}"
    }

    fun classify(description: String): Kind = when {
        "Horizon AI 2" in description && "textura estimada" in description -> Kind.NEURAL
        "detalhe multiframe" in description || "quadros" in description -> Kind.FUSION
        "detalhe nativo" in description -> Kind.NATIVE
        else -> Kind.CAMERA
    }
}
