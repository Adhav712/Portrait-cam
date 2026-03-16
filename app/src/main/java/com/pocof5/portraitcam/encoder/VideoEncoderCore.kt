package com.pocof5.portraitcam.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Handles video encoding using MediaCodec and MP4 muxing.
 */
class VideoEncoderCore @Throws(IOException::class) constructor(
    width: Int,
    height: Int,
    bitRate: Int,
    outputFile: File,
    orientationHint: Int = 0
) {
    companion object {
        private const val TAG = "PortraitCam"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val FRAME_RATE = 30
        private const val IFRAME_INTERVAL = 1
        private const val TIMEOUT_USEC = 10000L
    }

    private var encoder: MediaCodec
    private var muxer: MediaMuxer
    private var trackIndex: Int = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()

    val inputSurface: Surface

    init {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)

        encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(orientationHint)
        trackIndex = -1
        muxerStarted = false
    }

    /**
     * Extracts all pending data from the encoder and forwards it to the muxer.
     * @param endOfStream if true, sends the EOS flag and stops writing.
     */
    fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) {
            Log.d(TAG, "sending EOS to encoder")
            encoder.signalEndOfInputStream()
        }

        while (true) {
            val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    break // out of while
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw RuntimeException("format changed twice")
                }
                val newFormat = encoder.outputFormat
                trackIndex = muxer.addTrack(newFormat)
                muxer.start()
                muxerStarted = true
            } else if (encoderStatus < 0) {
                // ignore
            } else {
                val encodedData: ByteBuffer = encoder.getOutputBuffer(encoderStatus)
                    ?: throw RuntimeException("encoderOutputBuffer $encoderStatus was null")

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size != 0) {
                    if (!muxerStarted) {
                        throw RuntimeException("muxer hasn't started")
                    }
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                }

                encoder.releaseOutputBuffer(encoderStatus, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly")
                    }
                    break // out of while
                }
            }
        }
    }

    /**
     * Releases encoder resources.
     */
    fun release() {
        Log.d(TAG, "releasing encoder objects")
        try {
            encoder.stop()
            encoder.release()
        } catch (e: Exception) {
            Log.e(TAG, "Exception releasing encoder", e)
        }
        if (muxerStarted) {
            try {
                muxer.stop()
                muxer.release()
            } catch (e: Exception) {
                Log.e(TAG, "Exception releasing muxer", e)
            }
        }
    }
}
