package com.pocof5.portraitcam

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.pocof5.portraitcam.encoder.VideoEncoderCore
import com.pocof5.portraitcam.opengl.PortraitBlurProgram
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class CameraRenderer(
    private val view: GLSurfaceView,
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val TAG = "PortraitCam"

        // Full-screen quad
        private val VERTEX_COORDS = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )
        private val TEXTURE_COORDS = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )
    }

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    private var program: PortraitBlurProgram? = null
    private var cameraTextureId: Int = -1
    private var maskTextureId: Int = -1
    var surfaceTexture: SurfaceTexture? = null

    private val stMatrix = FloatArray(16)
    // Identity matrix for the mask since it's an upright 2D texture
    private val maskMatrix = FloatArray(16)

    private var updateSurface = false
    private var viewWidth = 0
    private var viewHeight = 0

    // Async mask updates
    private val maskLock = Object()
    private var pendingMaskBuffer: FloatBuffer? = null
    private var pendingMaskWidth: Int = 0
    private var pendingMaskHeight: Int = 0

    // Video Recording state — SINGLE CONTEXT approach (no shared EGL context)
    private var recordingEnabled = false
    private var videoEncoder: VideoEncoderCore? = null
    private var outputFile: File? = null
    private var recordingOrientationHint = 0

    // Saved EGL state from GLSurfaceView's context (set in onSurfaceCreated)
    private var savedEglDisplay: android.opengl.EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var savedEglContext: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT
    private var savedEglConfig: EGLConfig? = null

    // Encoder EGL surface (created on the SAME context, different draw target)
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        vertexBuffer = ByteBuffer.allocateDirect(VERTEX_COORDS.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(VERTEX_COORDS)
        vertexBuffer.position(0)

        texCoordBuffer = ByteBuffer.allocateDirect(TEXTURE_COORDS.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(TEXTURE_COORDS)
        texCoordBuffer.position(0)

        Matrix.setIdentityM(maskMatrix, 0)
    }

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        Log.d(TAG, "onSurfaceCreated")
        
        // Save the GLSurfaceView's EGL display, context, and config for later reuse
        savedEglDisplay = EGL14.eglGetCurrentDisplay()
        savedEglContext = EGL14.eglGetCurrentContext()

        // Query the config ID of the current context and find the matching EGL14 config
        val configId = IntArray(1)
        EGL14.eglQueryContext(savedEglDisplay, savedEglContext, EGL14.EGL_CONFIG_ID, configId, 0)
        val attribList = intArrayOf(EGL14.EGL_CONFIG_ID, configId[0], EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(savedEglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        savedEglConfig = configs[0]
        Log.d(TAG, "Saved EGL config: id=${configId[0]}, config=${savedEglConfig}")

        // 1. Create the blur program
        program = PortraitBlurProgram()

        // 2. Create the OES texture for CameraX
        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        cameraTextureId = textures[0]
        maskTextureId = textures[1]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // 3. Create the standard 2D texture for MediaPipe mask
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(cameraTextureId)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st

        // Inform MainActivity that the SurfaceTexture is ready to be bound to CameraX
        view.post { onSurfaceTextureReady(st) }
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged: ${width}x$height")
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        val st = surfaceTexture ?: return

        var newFrame = false
        synchronized(this) {
            if (updateSurface) {
                updateSurface = false
                newFrame = true
            }
        }

        if (newFrame) {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
        }

        // Upload new mask asynchronously if available
        synchronized(maskLock) {
            val pending = pendingMaskBuffer
            if (pending != null) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 
                                    pendingMaskWidth, pendingMaskHeight, 0, 
                                    GLES20.GL_LUMINANCE, GLES20.GL_FLOAT, pending)
                pendingMaskBuffer = null
            }
        }

        // 1. Draw to display (GLSurfaceView's default surface)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        
        program?.draw(
            stMatrix, maskMatrix,
            vertexBuffer, 4, 2, 2 * 4,
            texCoordBuffer, 2 * 4,
            cameraTextureId, maskTextureId,
            viewWidth, viewHeight
        )

        // 2. Draw to video encoder if recording (SINGLE CONTEXT — just switch surfaces)
        if (recordingEnabled) {
            val encoder = videoEncoder
            
            val encWidth = 1080
            val encHeight = 1920
            
            if (encoder == null && outputFile != null) {
                // Initialize encoder on the GL thread
                try {
                    val enc = VideoEncoderCore(encWidth, encHeight, 6_000_000, outputFile!!, recordingOrientationHint)
                    videoEncoder = enc
                    
                    // Create encoder EGL surface on the SAME context — no shared context needed!
                    val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                    encoderEglSurface = EGL14.eglCreateWindowSurface(
                        savedEglDisplay, savedEglConfig, enc.inputSurface, surfaceAttribs, 0
                    )
                    if (encoderEglSurface == EGL14.EGL_NO_SURFACE) {
                        val err = EGL14.eglGetError()
                        Log.e(TAG, "Failed to create encoder EGL surface: error=$err")
                        videoEncoder?.release()
                        videoEncoder = null
                        recordingEnabled = false
                        outputFile = null
                        return
                    }
                    Log.d(TAG, "Encoder initialized: ${encWidth}x${encHeight}, single-context surface")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize video encoder", e)
                    recordingEnabled = false
                    outputFile = null
                    return
                }
            } else if (encoder != null && encoderEglSurface != EGL14.EGL_NO_SURFACE && newFrame) {
                val timestampNs = st.timestamp
                
                // Save GLSurfaceView's current surfaces
                val displayDrawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
                val displayReadSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
                
                // Switch to encoder surface (SAME context! No texture sharing issues!)
                EGL14.eglMakeCurrent(savedEglDisplay, encoderEglSurface, encoderEglSurface, savedEglContext)
                
                GLES20.glViewport(0, 0, encWidth, encHeight)
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                
                // Draw EXACT same frame to encoder
                program?.draw(
                    stMatrix, maskMatrix,
                    vertexBuffer, 4, 2, 2 * 4,
                    texCoordBuffer, 2 * 4,
                    cameraTextureId, maskTextureId,
                    encWidth, encHeight
                )
                
                // Set presentation timestamp and swap
                EGLExt.eglPresentationTimeANDROID(savedEglDisplay, encoderEglSurface, timestampNs)
                EGL14.eglSwapBuffers(savedEglDisplay, encoderEglSurface)
                
                // Drain the encoder
                encoder.drainEncoder(false)
                
                // Restore GLSurfaceView's surfaces (SAME context)
                EGL14.eglMakeCurrent(savedEglDisplay, displayDrawSurface, displayReadSurface, savedEglContext)
            }
        } else if (videoEncoder != null) {
            // Stop recording — clean up encoder
            videoEncoder?.drainEncoder(true)
            videoEncoder?.release()
            videoEncoder = null
            
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(savedEglDisplay, encoderEglSurface)
                encoderEglSurface = EGL14.EGL_NO_SURFACE
            }
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        synchronized(this) {
            updateSurface = true
        }
        view.requestRender()
    }

    private var internalMaskBuffer: FloatBuffer? = null

    /**
     * Accepts a new confidence mask from MediaPipe and makes a deep copy.
     * MediaPipe's buffer may be freed or overwritten rapidly while recording,
     * causing "glitches" if we don't copy it ourselves before GL upload.
     */
    fun updateMask(maskBuffer: FloatBuffer, width: Int, height: Int) {
        synchronized(maskLock) {
            val capacity = maskBuffer.capacity()
            if (internalMaskBuffer == null || internalMaskBuffer!!.capacity() != capacity) {
                internalMaskBuffer = ByteBuffer.allocateDirect(capacity * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
            }
            
            internalMaskBuffer!!.clear()
            maskBuffer.position(0)
            internalMaskBuffer!!.put(maskBuffer)
            internalMaskBuffer!!.position(0)
            
            pendingMaskBuffer = internalMaskBuffer
            pendingMaskWidth = width
            pendingMaskHeight = height
        }
        view.requestRender()
    }

    fun startRecording(file: File, orientationHint: Int = 0) {
        outputFile = file
        recordingOrientationHint = orientationHint
        recordingEnabled = true
    }

    fun stopRecording() {
        recordingEnabled = false
    }

    fun release() {
        program?.release()
    }
}
