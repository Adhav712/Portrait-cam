package com.pocof5.portraitcam.opengl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import java.nio.FloatBuffer

/**
 * Fragment shader that performs a single-pass cross-fade between a sharp OES texture (person)
 * and a Gaussian-blurred OES texture (background), controlled by an alpha mask texture.
 */
class PortraitBlurProgram {
    companion object {
        private const val TAG = "PortraitBlurProgram"

        private const val VERTEX_SHADER = """
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                // Apply the SurfaceTexture transform matrix to the camera coordinates
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        // Dual-ring bokeh blur: inner ring (8 taps) + outer ring (8 taps) + center = 17 taps
        // Much smoother & wider than previous 9-tap 3px kernel
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            
            // Raw camera feed
            uniform samplerExternalOES sTexture;
            
            // MediaPipe Confidence Mask (0.0 = background, 1.0 = person)
            uniform sampler2D sMask;
            
            // Render target resolution for blur offsets
            uniform vec2 uResolution;
            
            // Mask transform matrix
            uniform mat4 uMaskMatrix;

            void main() {
                vec4 maskCoord = uMaskMatrix * vec4(vTextureCoord, 0.0, 1.0);
                float confidence = texture2D(sMask, maskCoord.xy).r;
                
                // Feather the mask edges for smooth transition
                float mask = smoothstep(0.3, 0.7, confidence);
                
                // If it's definitely a person, skip blur for speed
                if (mask > 0.99) {
                    gl_FragColor = texture2D(sTexture, vTextureCoord);
                    return;
                }
                
                // Wide bokeh-style blur using dual concentric ring sampling
                // Inner ring: radius ~8px, Outer ring: radius ~16px
                vec2 pixelSize = 1.0 / uResolution;
                
                float innerR = 8.0;
                float outerR = 16.0;
                
                vec4 sum = texture2D(sTexture, vTextureCoord) * 0.12;  // center
                
                // Inner ring (8 samples at 45-degree intervals)
                sum += texture2D(sTexture, vTextureCoord + pixelSize * vec2( innerR,  0.0))     * 0.08;
                sum += texture2D(sTexture, vTextureCoord + pixelSize * vec2(-innerR,  0.0))     * 0.08;
                sum += texture2D(sTexture, vTextureCoord + pixelSize * vec2( 0.0,     innerR))  * 0.08;
                sum += texture2D(sTexture, vTextureCoord + pixelSize * vec2( 0.0,    -innerR))  * 0.08;
                sum += texture2D(sTexture, vTextureCoord + pixelSize * vec2( innerR * 0.707,  innerR * 0.707))  * 0.06;
                sum += texture2D(sTexture, vTextureCoord + pixelSize * vec2(-innerR * 0.707,  innerR * 0.707))  * 0.06;
                sum += texture2D(sTexture, vTextureCoord + pixelSize * vec2( innerR * 0.707, -innerR * 0.707))  * 0.06;
                sum += texture2D(sTexture, vTextureCoord + pixelSize * vec2(-innerR * 0.707, -innerR * 0.707))  * 0.06;
                
                // Outer ring (8 samples at offset angles for wider spread)
                sum += texture2D(sTexture, vTextureCoord + pixelSize * vec2( outerR,  0.0))     * 0.04;
                sum += texture2D(sTexture, vTextureCoord + pixelSize * vec2(-outerR,  0.0))     * 0.04;
                sum += texture2D(sTexture, vTextureCoord + pixelSize * vec2( 0.0,     outerR))  * 0.04;
                sum += texture2D(sTexture, vTextureCoord + pixelSize * vec2( 0.0,    -outerR))  * 0.04;
                sum += texture2D(sTexture, vTextureCoord + pixelSize * vec2( outerR * 0.707,  outerR * 0.707))  * 0.03;
                sum += texture2D(sTexture, vTextureCoord + pixelSize * vec2(-outerR * 0.707,  outerR * 0.707))  * 0.03;
                sum += texture2D(sTexture, vTextureCoord + pixelSize * vec2( outerR * 0.707, -outerR * 0.707))  * 0.03;
                sum += texture2D(sTexture, vTextureCoord + pixelSize * vec2(-outerR * 0.707, -outerR * 0.707))  * 0.03;
                
                vec4 sharp = texture2D(sTexture, vTextureCoord);
                
                // Mix sharp and blurred based on smooth mask confidence
                gl_FragColor = mix(sum, sharp, mask);
            }
        """
    }

    private var programHandle: Int = 0
    private var muSTMatrixLoc: Int = 0
    private var muMaskMatrixLoc: Int = 0
    private var muResolutionLoc: Int = 0
    private var maPositionLoc: Int = 0
    private var maTextureCoordLoc: Int = 0

    init {
        programHandle = ShaderUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (programHandle == 0) {
            throw RuntimeException("Unable to create program")
        }
        maPositionLoc = GLES20.glGetAttribLocation(programHandle, "aPosition")
        maTextureCoordLoc = GLES20.glGetAttribLocation(programHandle, "aTextureCoord")
        muSTMatrixLoc = GLES20.glGetUniformLocation(programHandle, "uSTMatrix")
        muMaskMatrixLoc = GLES20.glGetUniformLocation(programHandle, "uMaskMatrix")
        muResolutionLoc = GLES20.glGetUniformLocation(programHandle, "uResolution")
    }

    fun draw(
        stMatrix: FloatArray,
        maskMatrix: FloatArray,
        vertexBuffer: FloatBuffer,
        vertexCount: Int,
        coordsPerVertex: Int,
        vertexStride: Int,
        texCoordBuffer: FloatBuffer,
        texCoordStride: Int,
        textureId: Int,
        maskTextureId: Int,
        width: Int,
        height: Int
    ) {
        ShaderUtil.checkGlError("draw start")

        GLES20.glUseProgram(programHandle)
        ShaderUtil.checkGlError("glUseProgram")

        // Bind OES camera texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        val sTextureLoc = GLES20.glGetUniformLocation(programHandle, "sTexture")
        GLES20.glUniform1i(sTextureLoc, 0)

        // Bind Mask 2D texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
        val sMaskLoc = GLES20.glGetUniformLocation(programHandle, "sMask")
        GLES20.glUniform1i(sMaskLoc, 1)

        // Set transforms and resolution
        GLES20.glUniformMatrix4fv(muSTMatrixLoc, 1, false, stMatrix, 0)
        GLES20.glUniformMatrix4fv(muMaskMatrixLoc, 1, false, maskMatrix, 0)
        GLES20.glUniform2f(muResolutionLoc, width.toFloat(), height.toFloat())

        // Enable arrays
        GLES20.glEnableVertexAttribArray(maPositionLoc)
        GLES20.glVertexAttribPointer(
            maPositionLoc, coordsPerVertex,
            GLES20.GL_FLOAT, false, vertexStride, vertexBuffer
        )

        GLES20.glEnableVertexAttribArray(maTextureCoordLoc)
        GLES20.glVertexAttribPointer(
            maTextureCoordLoc, 2,
            GLES20.GL_FLOAT, false, texCoordStride, texCoordBuffer
        )

        // Draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertexCount)

        // Clean up
        GLES20.glDisableVertexAttribArray(maPositionLoc)
        GLES20.glDisableVertexAttribArray(maTextureCoordLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)
    }

    fun release() {
        GLES20.glDeleteProgram(programHandle)
        programHandle = -1
    }
}
