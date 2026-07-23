package com.jing.camera.camera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Video recorder using MediaRecorder with Camera2.
 * Supports 1080p/30fps basic recording.
 */
class VideoRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VideoRecorder"
        private const val VIDEO_WIDTH = 1920
        private const val VIDEO_HEIGHT = 1080
        private const val VIDEO_FRAME_RATE = 30
        private const val VIDEO_BIT_RATE = 6000000
    }

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentFile: File? = null
    private val handlerThread = HandlerThread("VideoHandler").apply { start() }
    private val handler = Handler(handlerThread.looper)

    var onVideoSaved: ((File) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Prepare the video recorder with the camera device's surface.
     */
    fun prepare(device: CameraDevice, surface: Surface) {
        try {
            val timestamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US).format(Date())
            val videoFile = File(
                context.getExternalFilesDir(null), "VID_${timestamp}.mp4"
            )
            currentFile = videoFile

            mediaRecorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
                setVideoFrameRate(VIDEO_FRAME_RATE)
                setVideoEncodingBitRate(VIDEO_BIT_RATE)
                setOutputFile(videoFile.absolutePath)
                setInputSurface(surface)
                prepare()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare video recorder", e)
            onError?.invoke("准备录像失败: ${e.message}")
        }
    }

    /**
     * Start video recording.
     */
    fun startRecording() {
        if (isRecording) return

        try {
            mediaRecorder?.start()
            isRecording = true
            Log.d(TAG, "Video recording started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            onError?.invoke("开始录像失败: ${e.message}")
        }
    }

    /**
     * Stop video recording and save the file.
     */
    fun stopRecording() {
        if (!isRecording) return

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            Log.d(TAG, "Video recording stopped")

            currentFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    onVideoSaved?.invoke(file)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            onError?.invoke("停止录像失败: ${e.message}")
        }

        mediaRecorder = null
        isRecording = false
    }

    /**
     * Get the input surface for camera recording.
     */
    fun getInputSurface(): Surface? {
        return mediaRecorder?.surface
    }

    fun isRecording(): Boolean = isRecording

    /**
     * Release resources.
     */
    fun release() {
        stopRecording()
        handlerThread.quitSafely()
    }
}
