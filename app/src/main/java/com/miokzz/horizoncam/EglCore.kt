package com.miokzz.horizoncam

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import java.util.IdentityHashMap

internal class EglCore {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private val windows = IdentityHashMap<Surface, EGLSurface>()

    fun init() {
        check(display == EGL14.EGL_NO_DISPLAY) { "EGL already initialized" }

        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "eglInitialize failed" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, EGL14.EGL_TRUE,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No recordable EGL config"
        }
        config = configs[0] ?: error("Null EGL config")

        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        checkEgl("eglCreateContext")

        pbuffer = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0
        )
        checkEgl("eglCreatePbufferSurface")
        makeCurrent(pbuffer)
    }

    fun register(surface: Surface) {
        if (!windows.containsKey(surface)) {
            windows[surface] = EGL14.EGL_NO_SURFACE
        }
    }

    fun unregister(surface: Surface) {
        val egl = windows.remove(surface) ?: return
        if (egl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, egl)
        }
        makeCurrent(pbuffer)
    }

    fun draw(surface: Surface, width: Int, height: Int, timestampNs: Long, block: () -> Unit) {
        var egl = windows[surface] ?: error("Output Surface not registered")
        if (egl == EGL14.EGL_NO_SURFACE) {
            egl = EGL14.eglCreateWindowSurface(
                display,
                config,
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0
            )
            checkEgl("eglCreateWindowSurface")
            windows[surface] = egl
        }

        makeCurrent(egl)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        block()
        checkGl("draw")

        EGLExt.eglPresentationTimeANDROID(display, egl, timestampNs)
        check(EGL14.eglSwapBuffers(display, egl)) {
            "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }
    }

    fun release() {
        if (display == EGL14.EGL_NO_DISPLAY) return

        windows.values.forEach { egl ->
            if (egl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, egl)
        }
        windows.clear()

        EGL14.eglMakeCurrent(
            display,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )

        if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        EGL14.eglReleaseThread()

        pbuffer = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        config = null
    }

    private fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
            "eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }
    }

    private fun checkEgl(op: String) {
        val error = EGL14.eglGetError()
        check(error == EGL14.EGL_SUCCESS) { "$op EGL error 0x${Integer.toHexString(error)}" }
    }

    private fun checkGl(op: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "$op GL error 0x${Integer.toHexString(error)}" }
    }
}
