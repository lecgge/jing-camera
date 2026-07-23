package com.jing.camera.camera

import android.media.Image
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Night mode processor.
 * Captures multiple frames with longer exposure, aligns and merges
 * with aggressive noise reduction, applies brightness boost.
 */
object NightPipeline {
    private const val TAG = "NightPipeline"
    private const val DEFAULT_FRAMES = 8

    /**
     * Process a burst of low-light images into a single bright output JPEG.
     */
    fun processBurst(images: List<Image>, frameCount: Int = DEFAULT_FRAMES): ByteArray {
        if (images.isEmpty()) return ByteArray(0)

        val framesToProcess = if (images.size > frameCount) images.take(frameCount) else images

        val width = framesToProcess[0].width
        val height = framesToProcess[0].height

        Log.d(TAG, "Processing ${framesToProcess.size} night frames at ${width}x${height}")

        // Convert all frames to ARGB
        val frames = framesToProcess.map { imageToArgb(it) }

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

            // More aggressive merge for night mode
            NativeProcessor.mergeFrames(
                alignedFrames.toTypedArray(),
                output,
                alignedFrames.size,
                width,
                height,
                5.0f  // higher strength = more denoising
            )
        } else {
            System.arraycopy(reference, 0, output, 0, output.size)
        }

        // Night-specific processing
        NativeProcessor.nightEnhance(output, width, height)

        return argbToJpeg(output, width, height)
    }

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

    private fun argbToJpeg(pixels: IntArray, width: Int, height: Int): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(
            width, height, android.graphics.Bitmap.Config.ARGB_8888
        )
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val stream = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }
}
