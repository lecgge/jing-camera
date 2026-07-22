package com.jing.camera.camera

/**
 * JNI bridge to native image processing functions.
 */
object NativeProcessor {
    init {
        System.loadLibrary("jing_camera")
    }

    /**
     * Convert YUV_420_888 to ARGB_8888 pixel array.
     */
    external fun yuvToArgb(
        yBuffer: java.nio.ByteBuffer, yRowStride: Int, yPixelStride: Int,
        uBuffer: java.nio.ByteBuffer, uRowStride: Int, uPixelStride: Int,
        vBuffer: java.nio.ByteBuffer, vRowStride: Int, vPixelStride: Int,
        width: Int, height: Int,
        outPixels: IntArray
    )

    /**
     * Apply tone mapping curve.
     * @param strength 0.0-2.0, 1.0 = neutral
     */
    external fun toneMap(pixels: IntArray, width: Int, height: Int, strength: Float)

    /**
     * Apply color adjustments.
     * @param saturation 0.0-2.0, 1.0 = neutral
     * @param contrast 0.0-2.0, 1.0 = neutral
     */
    external fun colorAdjust(pixels: IntArray, width: Int, height: Int, saturation: Float, contrast: Float)
}
