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

    /**
     * Align two frames using block matching.
     * Returns motion vectors for each 8x8 block.
     */
    external fun alignFrames(reference: IntArray, toAlign: IntArray, width: Int, height: Int, motionVectors: FloatArray)

    /**
     * Warp a frame using motion vectors for alignment.
     */
    external fun warpFrame(src: IntArray, dst: IntArray, width: Int, height: Int, motionVectors: FloatArray)

    /**
     * Merge multiple aligned frames with temporal denoising.
     * @param strength higher = more aggressive noise removal
     */
    external fun mergeFrames(frameArray: Array<IntArray>, output: IntArray, frameCount: Int, width: Int, height: Int, strength: Float)

    /**
     * Night mode enhancement: brightness boost, shadow lifting, contrast.
     */
    external fun nightEnhance(pixels: IntArray, width: Int, height: Int)
}
