package com.pocof5.portraitcam.opengl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

/**
 * Core EGL state (display, context, config).
 */
class EglCore(sharedContext: EGLContext? = null) {
    companion object {
        private const val TAG = "PortraitCam"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private set
    var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private set
    private var eglConfig: EGLConfig? = null

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY
            throw RuntimeException("unable to initialize EGL14")
        }

        val sharedCtx = sharedContext ?: EGL14.EGL_NO_CONTEXT

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(
                eglDisplay, attribList, 0, configs,
                0, configs.size, numConfigs, 0
            ) || numConfigs[0] <= 0
        ) {
            // Fallback to ES2
            attribList[9] = EGL14.EGL_OPENGL_ES2_BIT
            if (!EGL14.eglChooseConfig(
                    eglDisplay, attribList, 0, configs,
                    0, configs.size, numConfigs, 0
                ) || numConfigs[0] <= 0
            ) {
                throw RuntimeException("unable to find RGB8888 / recordable ES2/ES3 EGL config")
            }
        }
        eglConfig = configs[0]

        val ctxAttribList = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, // Try ES3
            EGL14.EGL_NONE
        )
        var context = EGL14.eglCreateContext(eglDisplay, eglConfig, sharedCtx, ctxAttribList, 0)
        
        if (EGL14.eglGetError() != EGL14.EGL_SUCCESS || context == EGL14.EGL_NO_CONTEXT) {
            // Fallback to ES2
            ctxAttribList[1] = 2
            context = EGL14.eglCreateContext(eglDisplay, eglConfig, sharedCtx, ctxAttribList, 0)
        }
        if (EGL14.eglGetError() != EGL14.EGL_SUCCESS || context == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Failed to create EGL context")
        }
        eglContext = context
    }

    fun release() {
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
    }

    fun createWindowSurface(surface: Any): EGLSurface {
        if (surface !is Surface && surface !is android.graphics.SurfaceTexture) {
            throw RuntimeException("invalid surface: $surface")
        }
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, surface,
            surfaceAttribs, 0
        )
        if (eglSurface == null || EGL14.eglGetError() != EGL14.EGL_SUCCESS) {
            throw RuntimeException("surface was null or eglCreateWindowSurface failed")
        }
        return eglSurface
    }

    fun makeCurrent(eglSurface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.w(TAG, "eglMakeCurrent failed")
        }
    }

    fun swapBuffers(eglSurface: EGLSurface): Boolean {
        return EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun setPresentationTime(eglSurface: EGLSurface, nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    fun releaseSurface(eglSurface: EGLSurface) {
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
    }
}
