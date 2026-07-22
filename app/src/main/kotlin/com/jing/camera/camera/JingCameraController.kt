package com.jing.camera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Core camera controller for "Jing" — TextureView-based Camera2 implementation.
 * Handles preview, photo capture, flash, focus, and zoom.
 */
class JingCameraController(private val context: Context) {

    companion object {
        private const val TAG = "JingCameraController"
        private const val PREVIEW_WIDTH = 1920
        private const val PREVIEW_HEIGHT = 1080
        private const val MAX_IMAGES = 3
    }

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val backgroundThread = HandlerThread("JingCameraBackground").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)
    private val executor: Executor = Executor { command -> backgroundHandler.post(command) }

    var characteristics: CameraCharacteristics? = null
        private set

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null

    private var cameraId: String = ""
    private var isFrontCamera: Boolean = false
    private var previewSize: Size = Size(PREVIEW_WIDTH, PREVIEW_HEIGHT)
    private var sensorOrientation: Int = 0
    private var flashSupported: Boolean = false
    private var maxZoom: Float = 1f
    private var currentZoom: Float = 1f

    private var previewSurface: Surface? = null
    private var textureView: TextureView? = null

    var onPhotoCaptured: ((Image) -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null

    // Flash state
    enum class FlashMode { OFF, ON, AUTO }
    var flashMode: FlashMode = FlashMode.OFF
        private set

    fun nextFlashMode() {
        flashMode = when (flashMode) {
            FlashMode.OFF -> FlashMode.ON
            FlashMode.ON -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.OFF
        }
    }

    fun getCameraIds(): List<String> = cameraManager.cameraIdList.toList()

    fun getCharacteristics(cameraId: String): CameraCharacteristics =
        cameraManager.getCameraCharacteristics(cameraId)

    fun openCamera(textureView: TextureView, cameraId: String) {
        this.textureView = textureView
        this.cameraId = cameraId
        this.characteristics = cameraManager.getCameraCharacteristics(cameraId)

        val chars = characteristics!!
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        flashSupported = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

        val scaler = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = scaler?.getOutputSizes(SurfaceTexture::class.java)
        if (sizes != null && sizes.isNotEmpty()) {
            previewSize = chooseOptimalSize(sizes, PREVIEW_WIDTH, PREVIEW_HEIGHT)
        }

        val zoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
        maxZoom = zoom ?: 1f

        isFrontCamera = chars.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT

        if (textureView.isAvailable) {
            val surfaceTexture = textureView.surfaceTexture!!
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface = Surface(surfaceTexture)
            openCameraDevice()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    surface.setDefaultBufferSize(previewSize.width, previewSize.height)
                    previewSurface = Surface(surface)
                    openCameraDevice()
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {}

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    previewSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }

    private fun openCameraDevice() {
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    Log.e(TAG, "Camera error: $error")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return

        try {
            imageReader = ImageReader.newInstance(
                previewSize.width, previewSize.height,
                ImageFormat.JPEG, MAX_IMAGES
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    onPhotoCaptured?.invoke(image)
                }, backgroundHandler)
            }

            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            val surfaces = mutableListOf(surface)
            imageReader?.surface?.let { surfaces.add(it) }

            createCaptureSession(device, surfaces) { session ->
                try {
                    session.setRepeatingRequest(
                        previewRequestBuilder!!.build(),
                        captureCallback,
                        backgroundHandler
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start repeating request", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start preview", e)
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {}
    }

    fun capturePhoto() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        val session = captureSession ?: return

        try {
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation())

                when (flashMode) {
                    FlashMode.OFF -> {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    }
                    FlashMode.ON -> {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                    }
                    FlashMode.AUTO -> {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                    }
                }
            }

            session.capture(captureBuilder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture photo", e)
        }
    }

    fun setZoom(zoom: Float) {
        val chars = characteristics ?: return
        currentZoom = zoom.coerceIn(1f, maxZoom)

        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val newWidth = (sensorRect.width() / currentZoom).toInt()
        newWidth.coerceAtLeast(1)
        val newHeight = (sensorRect.height() / currentZoom).toInt()
        newHeight.coerceAtLeast(1)

        val centerX = sensorRect.width() / 2
        val centerY = sensorRect.height() / 2
        val halfWidth = newWidth / 2
        val halfHeight = newHeight / 2

        val cropRect = android.graphics.Rect(
            (centerX - halfWidth).coerceAtLeast(0),
            (centerY - halfHeight).coerceAtLeast(0),
            (centerX + halfWidth).coerceAtMost(sensorRect.width()),
            (centerY + halfHeight).coerceAtMost(sensorRect.height())
        )

        previewRequestBuilder?.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
        updateRepeatingRequest()
        onZoomChanged?.invoke(currentZoom)
    }

    private fun updateRepeatingRequest() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update repeating request", e)
        }
    }

    fun focusAt(x: Float, y: Float, viewWidth: Float, viewHeight: Float) {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val chars = characteristics ?: return

        try {
            val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

            val y0 = (x / viewWidth) * sensorRect.height()
            val x0 = (y / viewHeight) * sensorRect.width()
            val touchSize = 150

            val focusRect = android.graphics.Rect(
                (x0 - touchSize).toInt().coerceAtLeast(0),
                (y0 - touchSize).toInt().coerceAtLeast(0),
                (x0 + touchSize).toInt().coerceAtMost(sensorRect.width()),
                (y0 + touchSize).toInt().coerceAtMost(sensorRect.height())
            )

            val rectangle = MeteringRectangle(focusRect, MeteringRectangle.METERING_WEIGHT_MAX)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rectangle))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rectangle))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)

            session.capture(builder.build(), null, backgroundHandler)

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Focus error", e)
        }
    }

    private fun getJpegOrientation(): Int {
        val chars = characteristics ?: return 0
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        return if (isFrontCamera) {
            (sensorOrientation + 270) % 360
        } else {
            sensorOrientation
        }
    }

    private fun createCaptureSession(
        camera: CameraDevice,
        surfaces: List<Surface>,
        onConfigured: (CameraCaptureSession) -> Unit
    ) {
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cameraDevice == null) return
                captureSession = session
                onConfigured(session)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Failed to configure capture session")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val outputConfigs = surfaces.map { OutputConfiguration(it) }
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                executor,
                callback
            )
            camera.createCaptureSession(sessionConfig)
        } else {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(surfaces, callback, null)
        }
    }

    private fun chooseOptimalSize(sizes: Array<Size>, targetWidth: Int, targetHeight: Int): Size {
        val targetRatio = targetWidth.toFloat() / targetHeight
        var optimalSize: Size? = null
        var minDiff = Float.MAX_VALUE

        for (size in sizes) {
            val ratio = size.width.toFloat() / size.height
            if (kotlin.math.abs(ratio - targetRatio) > 0.1f) continue
            val diff = kotlin.math.abs(size.height - targetHeight).toFloat() + kotlin.math.abs(size.width - targetWidth).toFloat()
            if (diff < minDiff) {
                optimalSize = size
                minDiff = diff
            }
        }

        if (optimalSize == null) {
            minDiff = Float.MAX_VALUE
            for (size in sizes) {
                val diff = kotlin.math.abs(size.height - targetHeight).toFloat() + kotlin.math.abs(size.width - targetWidth).toFloat()
                if (diff < minDiff) {
                    optimalSize = size
                    minDiff = diff
                }
            }
        }

        return optimalSize ?: sizes[0]
    }

    fun closeCamera() {
        try {
            captureSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Exception closing session", e)
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Exception closing device", e)
        }
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        previewSurface = null
    }

    fun release() {
        closeCamera()
        backgroundThread.quitSafely()
    }
}
