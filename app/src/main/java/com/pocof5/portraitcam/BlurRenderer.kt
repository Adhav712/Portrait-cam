package com.pocof5.portraitcam

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.nio.FloatBuffer

/**
 * CPU-based blur + mask compositing renderer.
 *
 * Takes a camera frame (Bitmap) and a segmentation confidence mask (FloatBuffer),
 * blurs the background, and composites person (sharp) + background (blurred).
 */
class BlurRenderer {

    companion object {
        private const val TAG = "PortraitCam"
    }

    var blurRadius: Int = 25

    /**
     * Composite the camera frame with a blurred background using the segmentation mask.
     *
     * @param frame Camera frame bitmap (ARGB_8888)
     * @param mask Confidence mask FloatBuffer (0.0 = background, 1.0 = person)
     * @param maskWidth Width of the mask
     * @param maskHeight Height of the mask
     * @return Composited bitmap with blurred background
     */
    fun composite(frame: Bitmap, mask: FloatBuffer, maskWidth: Int, maskHeight: Int): Bitmap {
        val width = frame.width
        val height = frame.height

        // Create blurred version of the frame
        val blurred = stackBlur(frame, blurRadius)

        // Get pixel arrays
        val originalPixels = IntArray(width * height)
        val blurredPixels = IntArray(width * height)
        frame.getPixels(originalPixels, 0, width, 0, 0, width, height)
        blurred.getPixels(blurredPixels, 0, width, 0, 0, width, height)

        // Rewind the mask buffer
        mask.rewind()

        // Composite using mask
        val resultPixels = IntArray(width * height)
        val scaleX = maskWidth.toFloat() / width
        val scaleY = maskHeight.toFloat() / height

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x

                // Map pixel to mask coordinates
                val maskX = (x * scaleX).toInt().coerceIn(0, maskWidth - 1)
                val maskY = (y * scaleY).toInt().coerceIn(0, maskHeight - 1)
                val maskIdx = maskY * maskWidth + maskX

                // Get mask confidence (0.0 = background, 1.0 = person)
                val confidence = mask.get(maskIdx).coerceIn(0f, 1f)

                // Blend: person pixels from original, background from blurred
                val origPixel = originalPixels[idx]
                val blurPixel = blurredPixels[idx]

                val r = ((Color.red(origPixel) * confidence) +
                        (Color.red(blurPixel) * (1f - confidence))).toInt()
                val g = ((Color.green(origPixel) * confidence) +
                        (Color.green(blurPixel) * (1f - confidence))).toInt()
                val b = ((Color.blue(origPixel) * confidence) +
                        (Color.blue(blurPixel) * (1f - confidence))).toInt()

                resultPixels[idx] = Color.argb(255, r, g, b)
            }
        }

        blurred.recycle()

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Fast stack blur implementation.
     * Based on the algorithm by Mario Klingemann.
     */
    private fun stackBlur(source: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return source.copy(Bitmap.Config.ARGB_8888, true)

        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int
        var routsum: Int
        var goutsum: Int
        var boutsum: Int

        val dv = IntArray(256 * div)
        for (i in dv.indices) {
            dv[i] = i / div
        }

        var yi = 0
        var yw = 0

        val vmin = IntArray(maxOf(w, h))
        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var p: Int

        for (y in 0 until h) {
            rinsum = 0; ginsum = 0; binsum = 0
            routsum = 0; goutsum = 0; boutsum = 0
            rsum = 0; gsum = 0; bsum = 0
            for (i in -radius..radius) {
                p = pixels[yi + minOf(wm, maxOf(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rbs = r1 - kotlin.math.abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                }
            }
            stackpointer = radius
            for (x in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (y == 0) {
                    vmin[x] = minOf(x + radius + 1, wm)
                }
                p = pixels[yw + vmin[x]]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi++
            }
            yw = yi // Keep track for the start of next row
        }

        for (x in 0 until w) {
            rinsum = 0; ginsum = 0; binsum = 0
            routsum = 0; goutsum = 0; boutsum = 0
            rsum = 0; gsum = 0; bsum = 0
            var yp = -radius * w
            for (i in -radius..radius) {
                yi = maxOf(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]
                rbs = r1 - kotlin.math.abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                }
                if (i < hm) yp += w
            }
            yi = x
            stackpointer = radius
            for (y in 0 until h) {
                pixels[yi] = ((0xff shl 24) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum])
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (x == 0) {
                    vmin[y] = minOf(y + r1, hm) * w
                }
                p = x + vmin[y]
                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi += w
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }
}
