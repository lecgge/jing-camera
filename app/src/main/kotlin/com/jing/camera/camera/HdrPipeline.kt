package com.jing.camera.camera

import android.media.Image
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * HDR+ burst capture pipeline.
 * Captures multiple frames, aligns them, merges with temporal denoising,
 * applies tone mapping, and outputs JPEG.
 */
object HdrPipeline {
    private const val TAG = "HdrPipeline"
    private const val DEFAULT_FRAMES = 6

    /**
     * Process a burst of images into a single HDR output JPEG.
     */
    fun processBurst(images: List<Image>, frameCount: Int = DEFAULT_FRAMES): ByteArray {
        if (images.isEmpty()) return ByteArray(0)

        val framesToProcess = if (images.size > frameCount) images.take(frameCount) else images

        val width = framesToProcess[0].width
        val height = framesToProcess[0].height

        Log.d(TAG, "Processing ${framesToProcess.size} frames at ${width}x${height}")

        // Convert all frames to ARGB
        val frames = framesToProcess.map { imageToArgb(it) }

        // Use first frame as reference for alignment
        val reference = frames[0]
        val output = IntArray(width * height)

        if (frames.size >= 2) {
            val alignedFrames = mutableListOf(reference)

            for (i in 1 until frames.size) {
                val motionVectors = FloatArray((width / 8) * (height / 8) * 2)
                NativeProcessor.alignFrames(reference, frames[i], width, height, motionVectors)

                val aligned = IntArray(width * height)
                NativeProcessor.warpFrame(frames[i], aligned, width, height, motionVectors)
                alignedFrames.add(aligned)
            }

            NativeProcessor.mergeFrames(
                alignedFrames.toTypedArray(),
                output,
                alignedFrames.size,
                width,
                height,
                3.0f
            )
        } else {
            System.arraycopy(reference, 0, output, 0, output.size)
        }

        // Apply tone mapping for HDR look
        NativeProcessor.toneMap(output, width, height, 1.2f)

        // Subtle color enhancement
        NativeProcessor.colorAdjust(output, width, height, 1.05f, 1.1f)

        return argbToJpeg(output, width, height)
    }

    /**
     * Convert YUV_420_888 Image to ARGB IntArray.
     */
    private fun imageToArgb(image: Image): IntArray {
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        NativeProcessor.yuvToArgb(
            yPlane.buffer, yPlane.rowStride, yPlane.pixelStride,
            uPlane.buffer, uPlane.rowStride, uPlane.pixelStride,
            vPlane.buffer, vPlane.rowStride, vPlane.pixelStride,
            width, height, pixels
        )

        image.close()
        return pixels
    }

    /**
     * Convert ARGB pixels to JPEG byte array.
     */
    private fun argbToJpeg(pixels: IntArray, width: Int, height: Int): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(
            width, height, android.graphics.Bitmap.Config.ARGB_8888
        )
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val stream = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }
}
