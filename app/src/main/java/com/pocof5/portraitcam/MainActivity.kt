package com.pocof5.portraitcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult
import com.pocof5.portraitcam.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), ImageSegmenterHelper.SegmenterListener {

    companion object {
        private const val TAG = "PortraitCam"
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).toTypedArray()
    }

    private lateinit var binding: ActivityMainBinding

    private var cameraProvider: ProcessCameraProvider? = null
    private var isRecording = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // OpenGL & Rendering
    private lateinit var cameraRenderer: CameraRenderer

    // Segmentation
    private var segmenterHelper: ImageSegmenterHelper? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var lastProcessedTimestamp = 0L
    private var currentOutputFile: File? = null
    private var orientationEventListener: android.view.OrientationEventListener? = null
    private var currentDeviceRotation = 0

    // Timer
    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordingStartTime = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - recordingStartTime
            val seconds = (elapsed / 1000) % 60
            val minutes = (elapsed / 1000) / 60
            binding.timerText.text = String.format("%02d:%02d", minutes, seconds)
            timerHandler.postDelayed(this, 1000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            setupCameraAndGL()
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        orientationEventListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                currentDeviceRotation = when (orientation) {
                    in 45..134 -> 270 // Reverse landscape
                    in 135..224 -> 180 // Upside down
                    in 225..314 -> 90  // Landscape
                    else -> 0          // Portrait
                }
            }
        }
        orientationEventListener?.enable()

        if (allPermissionsGranted()) {
            setupCameraAndGL()
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }

        binding.recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        binding.flipCameraButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            // Need to recreate SurfaceTexture when flipping to ensure correct size/transform
            cameraProvider?.unbindAll()
            cameraRenderer.surfaceTexture?.let { 
                startCamera(it) 
            }
        }

        binding.galleryThumbnail.setOnClickListener {
            currentOutputFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            this,
                            "$packageName.provider",
                            file
                        )
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/mp4")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "No app to view video", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun setupCameraAndGL() {
        segmenterHelper = ImageSegmenterHelper(this, this)

        // Use a custom config chooser that requests EGL_RECORDABLE_ANDROID
        // so we can create encoder surfaces on the same context
        binding.glSurfaceView.setEGLContextClientVersion(3)
        binding.glSurfaceView.setEGLConfigChooser(RecordableConfigChooser())
        cameraRenderer = CameraRenderer(binding.glSurfaceView) { surfaceTexture ->
            // Called on the main thread when OpenGL has generated the SurfaceTexture
            startCamera(surfaceTexture)
        }
        binding.glSurfaceView.setRenderer(cameraRenderer)
        binding.glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    /**
     * Custom EGLConfigChooser that requests the EGL_RECORDABLE_ANDROID attribute.
     * This enables the GLSurfaceView's single context to create EGL window surfaces
     * for MediaCodec's input surface without needing a second shared context.
     */
    private class RecordableConfigChooser : GLSurfaceView.EGLConfigChooser {
        private val EGL_RECORDABLE_ANDROID = 0x3142

        override fun chooseConfig(egl: javax.microedition.khronos.egl.EGL10, display: javax.microedition.khronos.egl.EGLDisplay): javax.microedition.khronos.egl.EGLConfig {
            val attribs = intArrayOf(
                javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                EGL_RECORDABLE_ANDROID, 1,
                javax.microedition.khronos.egl.EGL10.EGL_NONE
            )
            
            val numConfigs = IntArray(1)
            egl.eglChooseConfig(display, attribs, null, 0, numConfigs)
            
            if (numConfigs[0] <= 0) {
                // Fallback: try without RECORDABLE or with ES2
                val fallbackAttribs = intArrayOf(
                    javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                    EGL_RECORDABLE_ANDROID, 1,
                    javax.microedition.khronos.egl.EGL10.EGL_NONE
                )
                egl.eglChooseConfig(display, fallbackAttribs, null, 0, numConfigs)
                
                if (numConfigs[0] <= 0) {
                    throw RuntimeException("No EGL config found with RECORDABLE_ANDROID")
                }
                
                val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(numConfigs[0])
                egl.eglChooseConfig(display, fallbackAttribs, configs, numConfigs[0], numConfigs)
                return configs[0]!!
            }
            
            val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(numConfigs[0])
            egl.eglChooseConfig(display, attribs, configs, numConfigs[0], numConfigs)
            return configs[0]!!
        }
    }

    private fun startCamera(surfaceTexture: android.graphics.SurfaceTexture) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // Pass the SurfaceTexture from our OpenGL renderer to CameraX
            val preview = Preview.Builder()
                .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
                .build().also {
                it.setSurfaceProvider { request: SurfaceRequest ->
                    surfaceTexture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                    val surface = Surface(surfaceTexture)
                    request.provideSurface(surface, ContextCompat.getMainExecutor(this)) { result ->
                        surface.release()
                    }
                }
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                analyzeFrame(imageProxy)
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                Log.d(TAG, "Camera started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
                Toast.makeText(this, "Camera setup failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val timestampMs = imageProxy.imageInfo.timestamp / 1_000_000

        // Throttle segmentation to preserve resources
        if (timestampMs - lastProcessedTimestamp < 30) {
            imageProxy.close()
            return
        }
        lastProcessedTimestamp = timestampMs

        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                val mpImage = BitmapImageBuilder(bitmap).build()
                segmenterHelper?.segmentAsync(mpImage, timestampMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame analysis error", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val planes = imageProxy.planes
        if (planes.isEmpty()) return null
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * imageProxy.width

        val bitmap = Bitmap.createBitmap(
            imageProxy.width + rowPadding / pixelStride,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, imageProxy.width, imageProxy.height)
        } else {
            bitmap
        }
    }

    override fun onSegmentationResult(result: ImageSegmenterResult, inputImage: MPImage) {
        val confidenceMasks = result.confidenceMasks()
        if (!confidenceMasks.isPresent || confidenceMasks.get().isEmpty()) return

        val mask = confidenceMasks.get()[0]
        try {
            val maskBuffer = ByteBufferExtractor.extract(mask).asFloatBuffer()
            cameraRenderer.updateMask(maskBuffer, mask.width, mask.height)
        } catch (e: Exception) {
            Log.e(TAG, "Error passing mask to renderer", e)
        }
    }

    override fun onSegmentationError(error: RuntimeException) {
        Log.e(TAG, "Segmentation error", error)
    }

    private fun getOutputFile(): File {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val appDir = File(moviesDir, "PortraitCam")
        if (!appDir.exists()) appDir.mkdirs()

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        return File(appDir, "VID_${name}.mp4")
    }

    private fun startRecording() {
        val outFile = getOutputFile()
        currentOutputFile = outFile
        
        // GL Thread will initialize the MediaCodec using the view's size
        binding.glSurfaceView.queueEvent {
            cameraRenderer.startRecording(outFile, currentDeviceRotation)
        }

        isRecording = true
        binding.recordButton.setImageResource(0)
        binding.recordButton.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_stop))
        binding.timerText.visibility = View.VISIBLE
        binding.flipCameraButton.isEnabled = false
        binding.flipCameraButton.alpha = 0.3f

        recordingStartTime = System.currentTimeMillis()
        timerHandler.post(timerRunnable)

        Toast.makeText(this, "Recording started -> ${outFile.name}", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        // Stop recording on the GL Thread
        binding.glSurfaceView.queueEvent {
            cameraRenderer.stopRecording()
        }

        isRecording = false
        binding.recordButton.setImageResource(0)
        binding.recordButton.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_record))
        binding.timerText.visibility = View.GONE
        binding.flipCameraButton.isEnabled = true
        binding.flipCameraButton.alpha = 1.0f

        timerHandler.removeCallbacks(timerRunnable)
        Toast.makeText(this, "Recording saved", Toast.LENGTH_SHORT).show()

        val savedFile = currentOutputFile
        timerHandler.postDelayed({
            if (savedFile?.exists() == true && savedFile.length() > 0) {
                Thread {
                    try {
                        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            android.media.ThumbnailUtils.createVideoThumbnail(
                                savedFile,
                                android.util.Size(128, 128),
                                null
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            android.media.ThumbnailUtils.createVideoThumbnail(
                                savedFile.absolutePath,
                                android.provider.MediaStore.Video.Thumbnails.MINI_KIND
                            )
                        }
                        runOnUiThread {
                            if (bitmap != null) {
                                binding.galleryThumbnail.setImageBitmap(bitmap)
                                binding.galleryThumbnail.visibility = View.VISIBLE
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed creating thumbnail", e)
                    }
                }.start()
            }
        }, 500)
    }

    override fun onResume() {
        super.onResume()
        if (::cameraRenderer.isInitialized) {
            binding.glSurfaceView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::cameraRenderer.isInitialized) {
            binding.glSurfaceView.onPause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(timerRunnable)
        segmenterHelper?.close()
        analysisExecutor.shutdown()
    }
}
