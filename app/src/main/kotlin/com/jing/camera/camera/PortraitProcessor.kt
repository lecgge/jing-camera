package com.jing.camera.camera

import android.media.Image
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.exp

/**
 * Portrait mode processor.
 * Creates bokeh effect by segmenting foreground from background.
 * Uses depth data if available, falls back to skin-color segmentation.
 */
object PortraitProcessor {
    private const val TAG = "PortraitProcessor"

    /**
     * Process an image with portrait bokeh effect.
     * @param jpegBytes Original JPEG image data
     * @param depthMap Optional depth map (from DEPTH16 or dual-pixel)
     * @param blurStrength Blur radius (1-20)
     */
    fun applyBokeh(jpegBytes: ByteArray, depthMap: FloatArray? = null, blurStrength: Int = 10): ByteArray {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        val width = bitmap.width
        val height = bitmap.height

        // Create segmentation mask
        val mask = if (depthMap != null) {
            createDepthMask(depthMap, width, height)
        } else {
            createSkinMask(bitmap)
        }

        // Apply blur to background
        val blurred = gaussianBlur(bitmap, blurStrength)

        // Composite: foreground from original, background from blurred
        val result = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        val origPixels = IntArray(width * height)
        bitmap.getPixels(origPixels, 0, width, 0, 0, width, height)

        val blurPixels = IntArray(width * height)
        blurred.getPixels(blurPixels, 0, width, 0, 0, width, height)

        for (i in 0 until width * height) {
            val maskVal = mask[i] // 0-255, 255 = foreground
            if (maskVal < 255) {
                val origA = (origPixels[i] shr 24) and 0xFF
                val origR = (origPixels[i] shr 16) and 0xFF
                val origG = (origPixels[i] shr 8) and 0xFF
                val origB = origPixels[i] and 0xFF

                val blurA = (blurPixels[i] shr 24) and 0xFF
                val blurR = (blurPixels[i] shr 16) and 0xFF
                val blurG = (blurPixels[i] shr 8) and 0xFF
                val blurB = blurPixels[i] and 0xFF

                val w = maskVal / 255.0f
                val r = ((origR * w + blurR * (1 - w)).toInt()).coerceIn(0, 255)
                val g = ((origG * w + blurG * (1 - w)).toInt()).coerceIn(0, 255)
                val b = ((origB * w + blurB * (1 - w)).toInt()).coerceIn(0, 255)
                val a = ((origA * w + blurA * (1 - w)).toInt()).coerceIn(0, 255)

                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        blurred.recycle()

        val stream = ByteArrayOutputStream()
        result.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, stream)
        result.recycle()
        return stream.toByteArray()
    }

    /**
     * Create mask from depth map.
     */
    private fun createDepthMask(depthMap: FloatArray, width: Int, height: Int): IntArray {
        val mask = IntArray(width * height)

        // Find min/max depth
        var minDepth = Float.MAX_VALUE
        var maxDepth = Float.MIN_VALUE
        for (d in depthMap) {
            if (d > 0 && d < minDepth) minDepth = d
            if (d > maxDepth) maxDepth = d
        }

        val range = maxDepth - minDepth
        if (range <= 0) return mask

        // Foreground = closer objects (smaller depth)
        // Center-biased: objects near center are more likely foreground
        val centerX = width / 2.0f
        val centerY = height / 2.0f
        val maxDist = kotlin.math.sqrt(centerX * centerX + centerY * centerY)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val depth = depthMap[idx]

                if (depth <= 0) {
                    mask[idx] = 0
                    continue
                }

                // Depth score: closer = higher score
                val depthScore = ((maxDepth - depth) / range * 255).toInt().coerceIn(0, 255)

                // Center score: closer to center = higher score
                val dx = x - centerX
                val dy = y - centerY
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                val centerScore = ((1 - dist / maxDist) * 255).toInt().coerceIn(0, 255)

                // Combined score
                mask[idx] = ((depthScore * 0.6f + centerScore * 0.4f).toInt()).coerceIn(0, 255)
            }
        }

        return smoothMask(mask, width, height)
    }

    /**
     * Create mask based on skin color detection.
     */
    private fun createSkinMask(bitmap: android.graphics.Bitmap): IntArray {
        val width = bitmap.width
        val height = bitmap.height
        val mask = IntArray(width * height)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val centerX = width / 2.0f
        val centerY = height / 2.0f
        val maxDist = kotlin.math.sqrt(centerX * centerX + centerY * centerY)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val pixel = pixels[idx]

                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Skin color detection in YCbCr space
                val yVal = 0.299f * r + 0.587f * g + 0.114f * b
                val cb = 128 - 0.168736f * r - 0.331264f * g + 0.5f * b
                val cr = 128 + 0.5f * r - 0.418688f * g - 0.081312f * b

                // Skin tone range
                var skinScore = 0
                if (yVal > 80 && cr in 133.0f..173.0f && cb in 77.0f..127.0f) {
                    skinScore = 255
                }

                // Center bias
                val dx = x - centerX
                val dy = y - centerY
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                val centerScore = ((1 - dist / maxDist) * 128).toInt().coerceIn(0, 128)

                mask[idx] = (skinScore + centerScore).coerceIn(0, 255)
            }
        }

        return smoothMask(mask, width, height)
    }

    /**
     * Smooth mask edges for natural transition.
     */
    private fun smoothMask(mask: IntArray, width: Int, height: Int): IntArray {
        val smoothed = IntArray(width * height)
        val radius = 3

        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                var count = 0

                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                            sum += mask[ny * width + nx]
                            count++
                        }
                    }
                }

                smoothed[y * width + x] = sum / count
            }
        }

        return smoothed
    }

    /**
     * Simple Gaussian blur implementation.
     */
    private fun gaussianBlur(bitmap: android.graphics.Bitmap, radius: Int): android.graphics.Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)

        // Create Gaussian kernel
        val size = radius * 2 + 1
        val kernel = FloatArray(size * size)
        val sigma = radius / 2.0f
        var sum = 0.0f

        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - radius
                val dy = y - radius
                val value = exp(-(dx * dx + dy * dy) / (2 * sigma * sigma))
                kernel[y * size + x] = value
                sum += value
            }
        }

        // Normalize
        for (i in kernel.indices) {
            kernel[i] /= sum
        }

        // Apply convolution
        val srcPixels = IntArray(width * height)
        bitmap.getPixels(srcPixels, 0, width, 0, 0, width, height)
        val dstPixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                var rSum = 0f
                var gSum = 0f
                var bSum = 0f
                var aSum = 0f

                for (ky in 0 until size) {
                    for (kx in 0 until size) {
                        val nx = (x + kx - radius).coerceIn(0, width - 1)
                        val ny = (y + ky - radius).coerceIn(0, height - 1)
                        val pixel = srcPixels[ny * width + nx]
                        val weight = kernel[ky * size + kx]

                        aSum += ((pixel shr 24) and 0xFF) * weight
                        rSum += ((pixel shr 16) and 0xFF) * weight
                        gSum += ((pixel shr 8) and 0xFF) * weight
                        bSum += (pixel and 0xFF) * weight
                    }
                }

                val a = aSum.toInt().coerceIn(0, 255)
                val r = rSum.toInt().coerceIn(0, 255)
                val g = gSum.toInt().coerceIn(0, 255)
                val b = bSum.toInt().coerceIn(0, 255)

                dstPixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        result.setPixels(dstPixels, 0, width, 0, 0, width, height)
        return result
    }
}
