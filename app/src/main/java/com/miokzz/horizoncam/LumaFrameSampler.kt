package com.miokzz.horizoncam

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads a small, already leveled image without reading the video or preview surfaces. */
internal class LumaFrameSampler {
    private val texture = IntArray(1)
    private val framebuffer = IntArray(1)
    private val pixels = ByteBuffer.allocateDirect(PIXEL_WIDTH * PIXEL_HEIGHT * 4).order(ByteOrder.nativeOrder())

    fun read(renderer: OesRenderer, textureMatrix: FloatArray, vertexMatrix: FloatArray, luma: ByteArray) {
        if (framebuffer[0] == 0) initialize()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
        try {
            GLES20.glViewport(0, 0, PIXEL_WIDTH, PIXEL_HEIGHT)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            renderer.draw(textureMatrix, vertexMatrix)
            pixels.position(0)
            GLES20.glReadPixels(0, 0, PIXEL_WIDTH, PIXEL_HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Amostra de movimento indisponível" }
            var index = 0
            for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
                val source = (y * PIXEL_WIDTH + x) * 8
                val gray = gray(source) + gray(source + 4) + gray(source + PIXEL_WIDTH * 4) + gray(source + PIXEL_WIDTH * 4 + 4)
                luma[index++] = (gray / 4).toByte()
            }
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
    }

    private fun gray(index: Int): Int {
        val r = pixels.get(index).toInt() and 255
        val g = pixels.get(index + 1).toInt() and 255
        val b = pixels.get(index + 2).toInt() and 255
        return (77 * r + 150 * g + 29 * b) shr 8
    }

    private fun initialize() {
        GLES20.glGenTextures(1, texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, PIXEL_WIDTH, PIXEL_HEIGHT, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glGenFramebuffers(1, framebuffer, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
        try {
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture[0], 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "Framebuffer de movimento incompleto"
            }
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
    }

    fun release() {
        GLES20.glDeleteFramebuffers(1, framebuffer, 0)
        GLES20.glDeleteTextures(1, texture, 0)
        framebuffer[0] = 0
        texture[0] = 0
    }

    companion object {
        const val WIDTH = 72
        const val HEIGHT = 40
        private const val PIXEL_WIDTH = WIDTH * 2
        private const val PIXEL_HEIGHT = HEIGHT * 2
    }
}
