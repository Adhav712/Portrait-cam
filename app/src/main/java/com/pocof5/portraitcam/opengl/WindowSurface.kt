package com.pocof5.portraitcam.opengl

import android.opengl.EGLSurface

/**
 * Wraps an EGLSurface and the associated EglCore.
 */
class WindowSurface(
    private val eglCore: EglCore,
    private val surface: Any
) {
    private var eglSurface: EGLSurface = eglCore.createWindowSurface(surface)

    fun makeCurrent() {
        eglCore.makeCurrent(eglSurface)
    }

    fun swapBuffers(): Boolean {
        return eglCore.swapBuffers(eglSurface)
    }

    fun setPresentationTime(nsecs: Long) {
        eglCore.setPresentationTime(eglSurface, nsecs)
    }

    fun release() {
        eglCore.releaseSurface(eglSurface)
    }
}
