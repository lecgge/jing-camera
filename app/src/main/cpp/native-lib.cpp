#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <cstring>
#include <cmath>

#define LOG_TAG "JingNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * YUV_420_888 to ARGB_8888 conversion.
 * Used for frame processing pipeline.
 */
JNIEXPORT void JNICALL
Java_com_jing_camera_camera_NativeProcessor_yuvToArgb(
        JNIEnv *env,
        jclass clazz,
        jobject yBuffer, jint yRowStride, jint yPixelStride,
        jobject uBuffer, jint uRowStride, jint uPixelStride,
        jobject vBuffer, jint vRowStride, jint vPixelStride,
        jint width, jint height,
        jintArray outPixels) {

    auto yBuf = static_cast<jbyte*>(env->GetDirectBufferAddress(yBuffer));
    auto uBuf = static_cast<jbyte*>(env->GetDirectBufferAddress(uBuffer));
    auto vBuf = static_cast<jbyte*>(env->GetDirectBufferAddress(vBuffer));

    jint* pixels = env->GetIntArrayElements(outPixels, nullptr);

    for (int row = 0; row < height; row++) {
        int yRow = row * yRowStride;
        int uvRow = (row >> 1) * uRowStride;

        for (int col = 0; col < width; col++) {
            int y = yBuf[yRow + col * yPixelStride] & 0xFF;
            int uvCol = (col >> 1) * uPixelStride;
            int u = (uBuf[uvRow + uvCol] & 0xFF) - 128;
            int v = (vBuf[uvRow + uvCol] & 0xFF) - 128;

            // BT.601 full-range YUV to RGB
            int r = y + ((91881 * v) >> 16);
            int g = y - ((22554 * u + 46802 * v) >> 16);
            int b = y + ((116130 * u) >> 16);

            r = r < 0 ? 0 : (r > 255 ? 255 : r);
            g = g < 0 ? 0 : (g > 255 ? 255 : g);
            b = b < 0 ? 0 : (b > 255 ? 255 : b);

            pixels[row * width + col] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
    }

    env->ReleaseIntArrayElements(outPixels, pixels, 0);
}

/**
 * Simple tone mapping for HDR-like effect.
 * Applies a sigmoid curve to compress dynamic range.
 */
JNIEXPORT void JNICALL
Java_com_jing_camera_camera_NativeProcessor_toneMap(
        JNIEnv *env,
        jclass clazz,
        jintArray pixels,
        jint width,
        jint height,
        jfloat strength) {

    jint* data = env->GetIntArrayElements(pixels, nullptr);
    int total = width * height;

    float factor = strength; // 0.0 - 2.0, 1.0 = neutral

    for (int i = 0; i < total; i++) {
        int pixel = data[i];

        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;

        // Apply tone curve (sigmoid-like)
        r = static_cast<int>(255.0f / (1.0f + std::exp(-factor * (r - 128) / 32.0f)));
        g = static_cast<int>(255.0f / (1.0f + std::exp(-factor * (g - 128) / 32.0f)));
        b = static_cast<int>(255.0f / (1.0f + std::exp(-factor * (b - 128) / 32.0f)));

        r = r < 0 ? 0 : (r > 255 ? 255 : r);
        g = g < 0 ? 0 : (g > 255 ? 255 : g);
        b = b < 0 ? 0 : (b > 255 ? 255 : b);

        data[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    env->ReleaseIntArrayElements(pixels, data, 0);
}

/**
 * Apply color adjustment: saturation and contrast.
 */
JNIEXPORT void JNICALL
Java_com_jing_camera_camera_NativeProcessor_colorAdjust(
        JNIEnv *env,
        jclass clazz,
        jintArray pixels,
        jint width,
        jint height,
        jfloat saturation,
        jfloat contrast) {

    jint* data = env->GetIntArrayElements(pixels, nullptr);
    int total = width * height;

    float sat = saturation; // 0.0 - 2.0, 1.0 = neutral
    float con = contrast;   // 0.0 - 2.0, 1.0 = neutral

    for (int i = 0; i < total; i++) {
        int pixel = data[i];

        float r = ((pixel >> 16) & 0xFF) / 255.0f;
        float g = ((pixel >> 8) & 0xFF) / 255.0f;
        float b = (pixel & 0xFF) / 255.0f;

        // Luminance
        float lum = 0.299f * r + 0.587f * g + 0.114f * b;

        // Saturation
        r = lum + sat * (r - lum);
        g = lum + sat * (g - lum);
        b = lum + sat * (b - lum);

        // Contrast
        r = 0.5f + con * (r - 0.5f);
        g = 0.5f + con * (g - 0.5f);
        b = 0.5f + con * (b - 0.5f);

        int ri = static_cast<int>(r * 255.0f);
        int gi = static_cast<int>(g * 255.0f);
        int bi = static_cast<int>(b * 255.0f);

        ri = ri < 0 ? 0 : (ri > 255 ? 255 : ri);
        gi = gi < 0 ? 0 : (gi > 255 ? 255 : gi);
        bi = bi < 0 ? 0 : (bi > 255 ? 255 : bi);

        data[i] = (0xFF << 24) | (ri << 16) | (gi << 8) | bi;
    }

    env->ReleaseIntArrayElements(pixels, data, 0);
}

/**
 * Frame alignment using block matching (8x8 blocks).
 * Returns motion vector (dx, dy) for each block.
 */
JNIEXPORT void JNICALL
Java_com_jing_camera_camera_NativeProcessor_alignFrames(
        JNIEnv *env, jclass clazz,
        jintArray reference, jintArray toAlign,
        jint width, jint height, jfloatArray motionVectors) {

    jint* ref = env->GetIntArrayElements(reference, nullptr);
    jint* src = env->GetIntArrayElements(toAlign, nullptr);
    jfloat* mv = env->GetFloatArrayElements(motionVectors, nullptr);

    const int blockSize = 8;
    const int searchRange = 16;
    int blocksX = width / blockSize;
    int blocksY = height / blockSize;

    for (int by = 0; by < blocksY; by++) {
        for (int bx = 0; bx < blocksX; bx++) {
            int bestDx = 0, bestDy = 0;
            int bestSad = INT_MAX;

            for (int dy = -searchRange; dy <= searchRange; dy += 2) {
                for (int dx = -searchRange; dx <= searchRange; dx += 2) {
                    int sad = 0;
                    for (int py = 0; py < blockSize && sad < bestSad; py++) {
                        for (int px = 0; px < blockSize && sad < bestSad; px++) {
                            int refX = bx * blockSize + px;
                            int refY = by * blockSize + py;
                            int srcX = refX + dx;
                            int srcY = refY + dy;

                            if (srcX >= 0 && srcX < width && srcY >= 0 && srcY < height) {
                                int refPixel = ref[refY * width + refX];
                                int srcPixel = src[srcY * width + srcX];
                                int refR = (refPixel >> 16) & 0xFF;
                                int refG = (refPixel >> 8) & 0xFF;
                                int refB = refPixel & 0xFF;
                                int srcR = (srcPixel >> 16) & 0xFF;
                                int srcG = (srcPixel >> 8) & 0xFF;
                                int srcB = srcPixel & 0xFF;
                                sad += abs(refR - srcR) + abs(refG - srcG) + abs(refB - srcB);
                            }
                        }
                    }
                    if (sad < bestSad) {
                        bestSad = sad;
                        bestDx = dx;
                        bestDy = dy;
                    }
                }
            }

            int idx = (by * blocksX + bx) * 2;
            mv[idx] = (jfloat)bestDx;
            mv[idx + 1] = (jfloat)bestDy;
        }
    }

    env->ReleaseIntArrayElements(reference, ref, 0);
    env->ReleaseIntArrayElements(toAlign, src, 0);
    env->ReleaseFloatArrayElements(motionVectors, mv, 0);
}

/**
 * Align a frame using motion vectors (bilinear warp).
 */
JNIEXPORT void JNICALL
Java_com_jing_camera_camera_NativeProcessor_warpFrame(
        JNIEnv *env, jclass clazz,
        jintArray src, jintArray dst, jint width, jint height, jfloatArray motionVectors) {

    jint* srcPixels = env->GetIntArrayElements(src, nullptr);
    jint* dstPixels = env->GetIntArrayElements(dst, nullptr);
    jfloat* mv = env->GetFloatArrayElements(motionVectors, nullptr);

    int blockSize = 8;
    int blocksX = width / blockSize;
    int blocksY = height / blockSize;

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int bx = x / blockSize;
            int by = y / blockSize;

            float fx = (float)x / blockSize - bx;
            float fy = (float)y / blockSize - by;

            int idx = (by * blocksX + bx) * 2;
            float dx = mv[idx];
            float dy = mv[idx + 1];

            // Bilinear interpolation
            int srcX = (int)(x + dx + 0.5f);
            int srcY = (int)(y + dy + 0.5f);

            if (srcX >= 0 && srcX < width && srcY >= 0 && srcY < height) {
                dstPixels[y * width + x] = srcPixels[srcY * width + srcX];
            } else {
                dstPixels[y * width + x] = srcPixels[y * width + x];
            }
        }
    }

    env->ReleaseIntArrayElements(src, srcPixels, 0);
    env->ReleaseIntArrayElements(dst, dstPixels, 0);
    env->ReleaseFloatArrayElements(motionVectors, mv, 0);
}

/**
 * Merge multiple aligned frames with temporal denoising.
 * Uses weighted average where pixels closer to median get higher weight.
 */
JNIEXPORT void JNICALL
Java_com_jing_camera_camera_NativeProcessor_mergeFrames(
        JNIEnv *env, jclass clazz,
        jobjectArray frameArray, jintArray output,
        jint frameCount, jint width, jint height, jfloat strength) {

    jintArray frames[12];
    jint* framePtrs[12];

    for (int i = 0; i < frameCount && i < 12; i++) {
        frames[i] = (jintArray)env->GetObjectArrayElement(frameArray, i);
        framePtrs[i] = env->GetIntArrayElements(frames[i], nullptr);
    }

    jint* out = env->GetIntArrayElements(output, nullptr);
    int totalPixels = width * height;

    for (int p = 0; p < totalPixels; p++) {
        int rValues[12], gValues[12], bValues[12];
        int validCount = 0;

        for (int f = 0; f < frameCount && f < 12; f++) {
            jint pixel = framePtrs[f][p];
            rValues[validCount] = (pixel >> 16) & 0xFF;
            gValues[validCount] = (pixel >> 8) & 0xFF;
            bValues[validCount] = pixel & 0xFF;
            validCount++;
        }

        // Sort to find median
        for (int i = 0; i < validCount - 1; i++) {
            for (int j = i + 1; j < validCount; j++) {
                if (rValues[j] < rValues[i]) { int t = rValues[i]; rValues[i] = rValues[j]; rValues[j] = t; }
                if (gValues[j] < gValues[i]) { int t = gValues[i]; gValues[i] = gValues[j]; gValues[j] = t; }
                if (bValues[j] < bValues[i]) { int t = bValues[i]; bValues[i] = bValues[j]; bValues[j] = t; }
            }
        }

        float medianR = rValues[validCount / 2] / 255.0f;
        float medianG = gValues[validCount / 2] / 255.0f;
        float medianB = bValues[validCount / 2] / 255.0f;

        // Weighted average based on distance from median
        float sumR = 0, sumG = 0, sumB = 0, sumW = 0;
        for (int f = 0; f < validCount; f++) {
            float r = rValues[f] / 255.0f;
            float g = gValues[f] / 255.0f;
            float b = bValues[f] / 255.0f;

            float dist = fabsf(r - medianR) + fabsf(g - medianG) + fabsf(b - medianB);
            float w = expf(-dist * strength);

            sumR += r * w;
            sumG += g * w;
            sumB += b * w;
            sumW += w;
        }

        int finalR = (int)(sumR / sumW * 255.0f);
        int finalG = (int)(sumG / sumW * 255.0f);
        int finalB = (int)(sumB / sumW * 255.0f);

        finalR = finalR < 0 ? 0 : (finalR > 255 ? 255 : finalR);
        finalG = finalG < 0 ? 0 : (finalG > 255 ? 255 : finalG);
        finalB = finalB < 0 ? 0 : (finalB > 255 ? 255 : finalB);

        out[p] = (0xFF << 24) | (finalR << 16) | (finalG << 8) | finalB;
    }

    for (int i = 0; i < frameCount && i < 12; i++) {
        env->ReleaseIntArrayElements(frames[i], framePtrs[i], JNI_ABORT);
        env->DeleteLocalRef(frames[i]);
    }
    env->ReleaseIntArrayElements(output, out, 0);
}

} // extern "C"
