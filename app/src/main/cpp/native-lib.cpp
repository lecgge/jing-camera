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

} // extern "C"
