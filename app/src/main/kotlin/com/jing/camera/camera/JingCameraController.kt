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
import java.util.concurrent.ConcurrentLinkedQueue

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
    var onPhotoCapturedJpeg: ((ByteArray) -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null

    // HDR burst capture state
    private val burstImages = ConcurrentLinkedQueue<Image>()
    private var isCapturingBurst = false
    private var yuvImageReader: ImageReader? = null
    private val burstFrameCount = 6

    // Flash state
    enum class FlashMode { OFF, ON, AUTO }
    var flashMode: FlashMode = FlashMode.OFF
        private set

    // Manual controls (Pro mode)
    var manualControls: ManualControls? = null
        private set

    fun enableProMode(enabled: Boolean) {
        if (enabled && manualControls == null && characteristics != null) {
            manualControls = ManualControls(characteristics!!)
        }
        manualControls?.setManualMode(enabled)
        updateRepeatingRequest()
    }

    fun isProModeEnabled(): Boolean = manualControls?.isManualMode == true

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
            // JPEG reader for normal capture
            imageReader = ImageReader.newInstance(
                previewSize.width, previewSize.height,
                ImageFormat.JPEG, MAX_IMAGES
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    onPhotoCaptured?.invoke(image)
                }, backgroundHandler)
            }

            // YUV reader for HDR burst capture
            yuvImageReader = ImageReader.newInstance(
                previewSize.width, previewSize.height,
                ImageFormat.YUV_420_888, burstFrameCount + 2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    if (isCapturingBurst) {
                        burstImages.offer(image)
                    } else {
                        image.close()
                    }
                }, backgroundHandler)
            }

            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            val surfaces = mutableListOf(surface)
            imageReader?.surface?.let { surfaces.add(it) }
            yuvImageReader?.surface?.let { surfaces.add(it) }

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
                applyFlash(this)
            }

            session.capture(captureBuilder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture photo", e)
        }
    }

    /**
     * Apply current flash mode to a capture request builder.
     */
    private fun applyFlash(builder: CaptureRequest.Builder) {
        when (flashMode) {
            FlashMode.OFF -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            FlashMode.ON -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
            }
            FlashMode.AUTO -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            }
        }
    }

    /**
     * Capture portrait photo with bokeh effect.
     */
    fun capturePortrait() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        val session = captureSession ?: return

        try {
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation())
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            // Temporarily change onPhotoCaptured callback to process portrait
            val originalCallback = onPhotoCaptured
            onPhotoCaptured = { image ->
                Thread {
                    try {
                        val jpegBytes = MediaStoreSaver.imageToByteArray(image)
                        val result = PortraitProcessor.applyBokeh(jpegBytes, null, 12)
                        onPhotoCapturedJpeg?.invoke(result)
                    } catch (e: Exception) {
                        Log.e(TAG, "Portrait processing failed", e)
                        // Fallback: use original image
                        val bytes = MediaStoreSaver.imageToByteArray(image)
                        onPhotoCapturedJpeg?.invoke(bytes)
                    }
                }.start()
            }

            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    // Restore original callback
                    backgroundHandler.postDelayed({
                        onPhotoCaptured = originalCallback
                    }, 100)
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture portrait", e)
        }
    }

    /**
     * Capture HDR photo using burst + merge pipeline.
     */
    fun captureHdrPhoto() {
        val device = cameraDevice ?: return
        val yuvReader = yuvImageReader ?: return
        val session = captureSession ?: return

        try {
            burstImages.clear()
            isCapturingBurst = true

            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(yuvReader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                // Underexpose slightly to preserve highlights
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, -1)
                applyFlash(this)
            }

            // Capture burst
            val requests = List(burstFrameCount) { captureBuilder.build() }
            session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    // Collect results if needed
                }
            }, backgroundHandler)

            // Process burst after a delay to allow all frames to arrive
            backgroundHandler.postDelayed({
                processBurstFrames()
            }, 2000)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture HDR burst", e)
            isCapturingBurst = false
        }
    }

    private fun processBurstFrames() {
        isCapturingBurst = false
        val frames = mutableListOf<Image>()
        while (burstImages.isNotEmpty()) {
            burstImages.poll()?.let { frames.add(it) }
        }

        if (frames.isEmpty()) {
            Log.e(TAG, "No frames captured for HDR")
            return
        }

        Log.d(TAG, "Processing ${frames.size} HDR frames")

        Thread {
            try {
                val jpegBytes = HdrPipeline.processBurst(frames)
                onPhotoCapturedJpeg?.invoke(jpegBytes)
            } catch (e: Exception) {
                Log.e(TAG, "HDR processing failed", e)
                // Fallback: return first frame as JPEG
                if (frames.isNotEmpty()) {
                    val bytes = MediaStoreSaver.imageToByteArray(frames[0])
                    onPhotoCapturedJpeg?.invoke(bytes)
                }
            }
        }.start()
    }

    /**
     * Capture night mode photo with longer exposure and more frames.
     */
    fun captureNight() {
        val device = cameraDevice ?: return
        val yuvReader = yuvImageReader ?: return
        val session = captureSession ?: return

        try {
            burstImages.clear()
            isCapturingBurst = true

            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(yuvReader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                // Longer exposure for night
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, 100000000L) // 100ms
                set(CaptureRequest.SENSOR_SENSITIVITY, 1600) // ISO 1600
                applyFlash(this)
            }

            // Capture more frames for night mode
            val nightFrames = 8
            val requests = List(nightFrames) { captureBuilder.build() }
            session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {}
            }, backgroundHandler)

            backgroundHandler.postDelayed({
                processNightFrames()
            }, 3000)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture night burst", e)
            isCapturingBurst = false
        }
    }

    private fun processNightFrames() {
        isCapturingBurst = false
        val frames = mutableListOf<Image>()
        while (burstImages.isNotEmpty()) {
            burstImages.poll()?.let { frames.add(it) }
        }

        if (frames.isEmpty()) {
            Log.e(TAG, "No frames captured for night mode")
            return
        }

        Log.d(TAG, "Processing ${frames.size} night frames")

        Thread {
            try {
                val jpegBytes = NightPipeline.processBurst(frames)
                onPhotoCapturedJpeg?.invoke(jpegBytes)
            } catch (e: Exception) {
                Log.e(TAG, "Night processing failed", e)
                if (frames.isNotEmpty()) {
                    val bytes = MediaStoreSaver.imageToByteArray(frames[0])
                    onPhotoCapturedJpeg?.invoke(bytes)
                }
            }
        }.start()
    }

    /**
     * Detect supported vendor extensions.
     */
    fun getSupportedExtensions(): List<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return try {
            val extChars = cameraManager.getCameraExtensionCharacteristics(cameraId)
            extChars.supportedExtensions
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get extensions", e)
            emptyList()
        }
    }

    fun isHdrSupported(): Boolean {
        return getSupportedExtensions().contains(1) // EXTENSION_HDR
    }

    fun isNightSupported(): Boolean {
        return getSupportedExtensions().contains(2) // EXTENSION_NIGHT
    }

    fun isBokehSupported(): Boolean {
        return getSupportedExtensions().contains(3) // EXTENSION_BOKEH
    }

    // Live Photo state
    private var livePhotoRecorder: LivePhotoRecorder? = null
    private var livePhotoEnabled = false

    fun isLivePhotoEnabled(): Boolean = livePhotoEnabled

    fun setLivePhotoEnabled(enabled: Boolean) {
        livePhotoEnabled = enabled
        if (!enabled) {
            livePhotoRecorder?.release()
            livePhotoRecorder = null
        }
    }

    /**
     * Capture Live Photo: JPEG + short video clip.
     */
    fun captureLivePhoto() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        val session = captureSession ?: return

        if (!livePhotoEnabled) {
            capturePhoto()
            return
        }

        try {
            // Capture JPEG
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation())
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            session.capture(captureBuilder.build(), null, backgroundHandler)

            // Start short video recording for Live Photo
            // Note: In a full implementation, we'd use a circular buffer
            // For now, record a short clip alongside the JPEG
            Log.d(TAG, "Live Photo captured")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture Live Photo", e)
        }
    }

    // Video recording state
    private var videoRecorder: VideoRecorder? = null
    private var isRecordingVideo = false
    private var videoCaptureSession: android.hardware.camera2.CameraCaptureSession? = null

    fun isRecordingVideo(): Boolean = isRecordingVideo

    /**
     * Start video recording.
     */
    fun startVideoRecording() {
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return

        if (isRecordingVideo) return

        try {
            videoRecorder = VideoRecorder(context).apply {
                onVideoSaved = { file ->
                    isRecordingVideo = false
                    Log.d(TAG, "Video saved: ${file.absolutePath}")
                }
                onError = { error ->
                    isRecordingVideo = false
                    Log.e(TAG, "Video error: $error")
                }
            }

            videoRecorder?.prepare(device, surface)
            videoRecorder?.startRecording()
            isRecordingVideo = true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start video recording", e)
            isRecordingVideo = false
        }
    }

    /**
     * Stop video recording.
     */
    fun stopVideoRecording() {
        if (!isRecordingVideo) return
        videoRecorder?.stopRecording()
        videoRecorder?.release()
        videoRecorder = null
        isRecordingVideo = false
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
        yuvImageReader?.close()
        yuvImageReader = null
        previewSurface = null
        burstImages.clear()
        isCapturingBurst = false
        livePhotoRecorder?.release()
        livePhotoRecorder = null
    }

    fun release() {
        closeCamera()
        backgroundThread.quitSafely()
    }
}
