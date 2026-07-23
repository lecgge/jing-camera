package com.jing.camera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import kotlin.math.log2
import kotlin.math.pow

/**
 * Manual camera controls for Pro mode.
 * Provides ISO, shutter speed, focus distance, and white balance control.
 */
class ManualControls(private val characteristics: CameraCharacteristics) {

    companion object {
        private const val TAG = "ManualControls"
    }

    // Supported ranges
    val isoRange: Range<Int> = characteristics.get(
        CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
    ) ?: Range(50, 3200)

    val exposureTimeRange: Range<Long> = characteristics.get(
        CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
    ) ?: Range(10000L, 300000000L) // 0.01ms to 300ms

    val focusRange: Range<Float> = run {
        val minFocus = characteristics.get(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        ) ?: 0f
        Range(minFocus, minFocus + 10f)
    }

    val hasManualSensor: Boolean
        get() {
            val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            return caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        }

    val hasManualFocus: Boolean
        get() = focusRange.upper > 0f

    // Current values
    var iso: Int = isoRange.lower
        private set

    var exposureNanos: Long = exposureTimeRange.lower
        private set

    var focusDistance: Float = focusRange.upper
        private set

    var whiteBalanceKelvin: Int = 5500
        private set

    var isManualMode = false
        private set

    // Listeners
    var onValueChanged: (() -> Unit)? = null

    fun setManualMode(enabled: Boolean) {
        isManualMode = enabled
        if (!enabled) {
            // Reset to auto values
            iso = isoRange.lower
            exposureNanos = exposureTimeRange.lower
            focusDistance = focusRange.upper
            whiteBalanceKelvin = 5500
        }
        onValueChanged?.invoke()
    }

    /**
     * Set ISO value (clamped to supported range).
     */
    fun setIso(value: Int) {
        iso = value.coerceIn(isoRange.lower, isoRange.upper)
        Log.d(TAG, "ISO set to: $iso")
        onValueChanged?.invoke()
    }

    /**
     * Set exposure time in nanoseconds (clamped to supported range).
     */
    fun setExposureTime(nanos: Long) {
        exposureNanos = nanos.coerceIn(exposureTimeRange.lower, exposureTimeRange.upper)
        Log.d(TAG, "Exposure set to: ${exposureNanos / 1_000_000}ms")
        onValueChanged?.invoke()
    }

    /**
     * Set focus distance in diopters (0 = infinity).
     */
    fun setFocusDistance(diopters: Float) {
        focusDistance = diopters.coerceIn(focusRange.lower, focusRange.upper)
        Log.d(TAG, "Focus set to: $focusDistance diopters")
        onValueChanged?.invoke()
    }

    /**
     * Set white balance in Kelvin (2300-7500).
     */
    fun setWhiteBalance(kelvin: Int) {
        whiteBalanceKelvin = kelvin.coerceIn(2300, 7500)
        Log.d(TAG, "WB set to: ${whiteBalanceKelvin}K")
        onValueChanged?.invoke()
    }

    /**
     * Apply manual settings to a CaptureRequest.Builder.
     */
    fun applyToRequest(builder: CaptureRequest.Builder) {
        if (!isManualMode) {
            // Auto mode
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            return
            return
        }

        // Manual sensor control
        if (hasManualSensor) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNanos)
        }

        // Manual focus
        if (hasManualFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
        }

        // Manual white balance
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        val gains = kelvinToRgbGains(whiteBalanceKelvin)
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
    }

    /**
     * Convert color temperature to RGB gains for white balance.
     */
    private fun kelvinToRgbGains(kelvin: Int): android.hardware.camera2.params.RggbChannelVector {
        val temp = kelvin / 100f
        var red: Float
        var green: Float
        var blue: Float

        if (temp <= 66) {
            red = 255f
            green = temp
            green = 99.4708025861f * kotlin.math.ln(green.toDouble()).toFloat() - 161.1195681661f

            if (temp <= 19) {
                blue = 0f
            } else {
                blue = temp - 10
                blue = 138.5177312231f * kotlin.math.ln(blue.toDouble()).toFloat() - 305.0447927307f
            }
        } else {
            red = temp - 60
            red = 329.698727446f * red.toDouble().pow(0.1332047592).toFloat()

            green = temp - 60
            green = 288.1221695283f * green.toDouble().pow(0.0755148492).toFloat()

            blue = 255f
        }

        val maxVal = maxOf(red, green, blue, 1f)
        return android.hardware.camera2.params.RggbChannelVector(
            (red / maxVal) * 2f,  // R gain
            (green / maxVal) * 1f, // G gain (both green channels)
            (green / maxVal) * 1f,
            (blue / maxVal) * 2f   // B gain
        )
    }

    /**
     * Format exposure time as human-readable string.
     */
    fun getExposureTimeString(): String {
        val ms = exposureNanos / 1_000_000f
        return if (ms >= 1000) {
            String.format("%.1fs", ms / 1000)
        } else if (ms >= 1) {
            String.format("%.0fms", ms)
        } else {
            String.format("1/%d", (1_000_000_000 / exposureNanos).coerceAtLeast(1))
        }
    }

    /**
     * Get supported ISO values (common steps).
     */
    fun getIsoSteps(): List<Int> {
        val steps = mutableListOf<Int>()
        var value = isoRange.lower
        while (value <= isoRange.upper) {
            steps.add(value)
            value = (value * 1.5f).toInt().coerceAtMost(isoRange.upper)
        }
        if (steps.lastOrNull() != isoRange.upper) steps.add(isoRange.upper)
        return steps
    }
}
