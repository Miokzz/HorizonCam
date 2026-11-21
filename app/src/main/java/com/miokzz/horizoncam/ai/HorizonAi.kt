package com.miokzz.horizoncam.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.min
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/** This is still-image inference, not a preview/video filter or recovered ground truth. */
data class AiOptions(
    val maxInputLongEdge: Int = 640,
    val strength: Float = 0.65f,
    val memoryBudgetMiB: Int = 192,
    val threads: Int = 2,
    val preserveChroma: Boolean = true,
)

data class AiProgress(val completedTiles: Int, val totalTiles: Int)

data class AiMetrics(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inputWidth: Int,
    val inputHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val completedTiles: Int,
    val totalTiles: Int,
    val elapsedMs: Long,
    val estimatedPeakMiB: Float,
    val noiseEstimate: Float,
    val edgeFraction: Float,
    val tensorDescription: String,
)

data class AiResult(val bitmap: Bitmap, val metrics: AiMetrics)

object HorizonAi {
    const val MODEL_ASSET = "ai/ESRGAN.tflite"
    const val MODEL_SHA256 = "1a380d3744103e11ef343534aaff54815cae40769dcd00c023652a7e5bc47f4b"
    const val MODEL_BYTES = 4_993_712

    /**
     * Blocking CPU work. Input must already have native upright orientation and software pixels.
     * Caller owns input and returned bitmap; this function never recycles caller input. Callbacks
     * occur on this worker thread, once per ACTUALLY inferred/composited tile. Cancellation is
     * checked between calls (one model invocation cannot be interrupted by a polling callback).
     */
    fun infer(
        context: Context,
        input: Bitmap,
        onProgress: (AiProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        options: AiOptions = AiOptions(),
    ): AiResult {
        require(Looper.myLooper() != Looper.getMainLooper()) {
            "Execute Horizon AI em uma thread de trabalho."
        }
        require(
            !input.isRecycled &&
                input.width > 0 &&
                input.height > 0 &&
                input.config != Bitmap.Config.HARDWARE
        ) {
            "Forneça uma imagem válida com pixels acessíveis, já orientada."
        }
        require(
            options.maxInputLongEdge in 64..768 &&
                options.threads in 1..4 &&
                options.memoryBudgetMiB in 96..256
        )
        require(options.strength.isFinite() && options.strength in 0f..1f)
        fun checkCancel() {
            if (isCancelled() || Thread.currentThread().isInterrupted)
                throw CancellationException("Horizon AI cancelado")
        }
        checkCancel()
        val started = System.nanoTime()
        val (inputWidth, inputHeight) =
            AiGeometry.scaledSize(input.width, input.height, options.maxInputLongEdge)
        val needsScale = inputWidth != input.width || inputHeight != input.height
        val estimate =
            AiGeometry.estimatedPeakBytes(
                input.allocationByteCount.toLong(),
                inputWidth,
                inputHeight,
                needsScale,
            )
        val budget =
            min(options.memoryBudgetMiB * 1024L * 1024, Runtime.getRuntime().maxMemory() * 3 / 4)
        require(estimate <= budget) {
            "Memória insuficiente para esta área. Reduza a imagem ou use um recorte menor."
        }
        var output: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val source =
                if (needsScale)
                    Bitmap.createScaledBitmap(input, inputWidth, inputHeight, true).also {
                        scaled = it
                    }
                else input
            val pixels = IntArray(inputWidth * inputHeight)
            source.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            val scene = AiColor.scene(pixels, inputWidth, inputHeight)
            val bicubic = AiBicubic(pixels, inputWidth, inputHeight)
            val outputWidth = inputWidth * AiGeometry.SCALE
            val outputHeight = inputHeight * AiGeometry.SCALE
            val destination =
                Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            output = destination
            val weights = FloatArray(outputWidth * outputHeight)
            val tilePixels =
                IntArray(AiGeometry.CORE * AiGeometry.SCALE * AiGeometry.CORE * AiGeometry.SCALE)
            val plan = AiGeometry.tiles(inputWidth, inputHeight)
            checkCancel()
            val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            require(modelBytes.size == MODEL_BYTES && sha256(modelBytes) == MODEL_SHA256) {
                "Modelo ESRGAN inválido ou alterado."
            }
            val model =
                ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder()).apply {
                    put(modelBytes)
                    rewind()
                }
            var completed = 0
            var tensorDescription = ""
            Interpreter(model, Interpreter.Options().setNumThreads(options.threads)).use {
                interpreter ->
                interpreter.allocateTensors()
                require(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 1) {
                    "Assinatura de modelo inesperada."
                }
                val inputTensor = interpreter.getInputTensor(0)
                val outputTensor = interpreter.getOutputTensor(0)
                val inputShape = inputTensor.shape()
                val outputShape = outputTensor.shape()
                require(
                    inputTensor.dataType() == DataType.FLOAT32 &&
                        outputTensor.dataType() == DataType.FLOAT32 &&
                        inputShape.contentEquals(intArrayOf(1, 50, 50, 3)) &&
                        outputShape.contentEquals(intArrayOf(1, 200, 200, 3))
                ) {
                    "Este pipeline requer ESRGAN RGB float32 50×50 → 200×200."
                }
                tensorDescription =
                    "${inputTensor.name()} ${inputShape.contentToString()} FLOAT32 RGB 0..255 → ${outputTensor.name()} ${outputShape.contentToString()} FLOAT32"
                val inputBuffer =
                    ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder())
                val outputBuffer =
                    ByteBuffer.allocateDirect(outputTensor.numBytes())
                        .order(ByteOrder.nativeOrder())
                onProgress(AiProgress(0, plan.size))
                for (tile in plan) {
                    checkCancel()
                    inputBuffer.rewind()
                    for (y in 0 until AiGeometry.TILE) {
                        val sampleY = AiGeometry.mirror(tile.y - AiGeometry.HALO + y, inputHeight)
                        for (x in 0 until AiGeometry.TILE) {
                            val sampleX =
                                AiGeometry.mirror(tile.x - AiGeometry.HALO + x, inputWidth)
                            val pixel = pixels[sampleY * inputWidth + sampleX]
                            inputBuffer.putFloat(AiColor.red(pixel).toFloat())
                            inputBuffer.putFloat(AiColor.green(pixel).toFloat())
                            inputBuffer.putFloat(AiColor.blue(pixel).toFloat())
                        }
                    }
                    inputBuffer.rewind()
                    outputBuffer.rewind()
                    interpreter.run(inputBuffer, outputBuffer)
                    checkCancel()
                    val tileOutputWidth = tile.width * 4
                    val tileOutputHeight = tile.height * 4
                    val outputOriginX = tile.x * 4
                    val outputOriginY = tile.y * 4
                    destination.getPixels(
                        tilePixels,
                        0,
                        tileOutputWidth,
                        outputOriginX,
                        outputOriginY,
                        tileOutputWidth,
                        tileOutputHeight,
                    )
                    for (y in 0 until tileOutputHeight) {
                        checkCancel()
                        for (x in 0 until tileOutputWidth) {
                            val globalX = outputOriginX + x
                            val globalY = outputOriginY + y
                            val modelIndex =
                                ((y + AiGeometry.HALO * 4) * 200 + x + AiGeometry.HALO * 4) * 3 * 4
                            val modelRed = outputBuffer.getFloat(modelIndex)
                            val modelGreen = outputBuffer.getFloat(modelIndex + 4)
                            val modelBlue = outputBuffer.getFloat(modelIndex + 8)
                            check(
                                modelRed.isFinite() && modelGreen.isFinite() && modelBlue.isFinite()
                            ) {
                                "Modelo produziu pixels inválidos."
                            }
                            val sourceX = (globalX / 4).coerceIn(0, inputWidth - 1)
                            val sourceY = (globalY / 4).coerceIn(0, inputHeight - 1)
                            val left = pixels[sourceY * inputWidth + (sourceX - 1).coerceAtLeast(0)]
                            val right =
                                pixels[
                                    sourceY * inputWidth +
                                        (sourceX + 1).coerceAtMost(inputWidth - 1)]
                            val up = pixels[(sourceY - 1).coerceAtLeast(0) * inputWidth + sourceX]
                            val down =
                                pixels[
                                    (sourceY + 1).coerceAtMost(inputHeight - 1) * inputWidth +
                                        sourceX]
                            val edge =
                                abs(AiColor.luma(right) - AiColor.luma(left)) +
                                    abs(AiColor.luma(down) - AiColor.luma(up))
                            val enhanced =
                                AiColor.mix(
                                    bicubic.pixel(globalX, globalY),
                                    modelRed,
                                    modelGreen,
                                    modelBlue,
                                    options.strength,
                                    scene.noiseEstimate,
                                    edge,
                                    options.preserveChroma,
                                )
                            val index = globalY * outputWidth + globalX
                            val previousWeight = weights[index]
                            val weight =
                                AiGeometry.weight(x, tileOutputWidth) *
                                    AiGeometry.weight(y, tileOutputHeight)
                            val totalWeight = previousWeight + weight
                            val previousPixel = tilePixels[y * tileOutputWidth + x]
                            tilePixels[y * tileOutputWidth + x] =
                                AiColor.pack(
                                    (AiColor.red(previousPixel) * previousWeight +
                                        AiColor.red(enhanced) * weight) / totalWeight,
                                    (AiColor.green(previousPixel) * previousWeight +
                                        AiColor.green(enhanced) * weight) / totalWeight,
                                    (AiColor.blue(previousPixel) * previousWeight +
                                        AiColor.blue(enhanced) * weight) / totalWeight,
                                )
                            weights[index] = totalWeight
                        }
                    }
                    destination.setPixels(
                        tilePixels,
                        0,
                        tileOutputWidth,
                        outputOriginX,
                        outputOriginY,
                        tileOutputWidth,
                        tileOutputHeight,
                    )
                    completed++
                    onProgress(AiProgress(completed, plan.size))
                }
            }
            checkCancel()
            val result =
                AiResult(
                    destination,
                    AiMetrics(
                        input.width,
                        input.height,
                        inputWidth,
                        inputHeight,
                        outputWidth,
                        outputHeight,
                        completed,
                        plan.size,
                        (System.nanoTime() - started) / 1_000_000,
                        estimate / (1024f * 1024),
                        scene.noiseEstimate,
                        scene.edgeFraction,
                        tensorDescription,
                    ),
                )
            output = null // ownership passes to caller only after all tiles complete
            return result
        } finally {
            output?.recycle()
            scaled?.recycle()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
}
