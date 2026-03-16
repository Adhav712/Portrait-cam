package com.pocof5.portraitcam

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult

class ImageSegmenterHelper(
    private val context: Context,
    private val listener: SegmenterListener
) {
    companion object {
        private const val TAG = "PortraitCam"
        private const val MODEL_PATH = "selfie_segmenter.tflite"
    }

    interface SegmenterListener {
        fun onSegmentationResult(result: ImageSegmenterResult, inputImage: MPImage)
        fun onSegmentationError(error: RuntimeException)
    }

    private var imageSegmenter: ImageSegmenter? = null

    init {
        setupSegmenter()
    }

    private fun setupSegmenter() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_PATH)
                .setDelegate(Delegate.CPU)
                .build()

            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setOutputCategoryMask(false)
                .setOutputConfidenceMasks(true)
                .setResultListener { result, inputImage ->
                    listener.onSegmentationResult(result, inputImage)
                }
                .setErrorListener { error ->
                    listener.onSegmentationError(error)
                }
                .build()

            imageSegmenter = ImageSegmenter.createFromOptions(context, options)
            Log.d(TAG, "ImageSegmenter initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ImageSegmenter", e)
            listener.onSegmentationError(
                RuntimeException("Failed to initialize segmenter: ${e.message}", e)
            )
        }
    }

    fun segmentAsync(mpImage: MPImage, timestampMs: Long) {
        imageSegmenter?.segmentAsync(mpImage, timestampMs)
    }

    fun close() {
        imageSegmenter?.close()
        imageSegmenter = null
    }
}
