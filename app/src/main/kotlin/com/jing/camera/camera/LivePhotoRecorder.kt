package com.jing.camera.camera

import android.content.Context
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
 * Live Photos recorder.
 * Captures a short video clip paired with a still JPEG.
 * Simplified implementation: records video when triggered.
 */
class LivePhotoRecorder(private val context: Context) {

    companion object {
        private const val TAG = "LivePhotoRecorder"
        private const val VIDEO_DURATION_MS = 3000L // 3 seconds
    }

    private var mediaRecorder: MediaRecorder? = null
    private val handlerThread = HandlerThread("LivePhotoHandler").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private var isRecording = false
    private var currentVideoFile: File? = null

    var onVideoSaved: ((File) -> Unit)? = null

    /**
     * Start recording a short video clip for Live Photo.
     */
    fun startRecording(surface: Surface) {
        if (isRecording) return

        try {
            val timestamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US).format(Date())
            val videoFile = File(context.cacheDir, "live_${timestamp}.mp4")
            currentVideoFile = videoFile

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
                setVideoSize(1920, 1080)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(6000000)
                setOutputFile(videoFile.absolutePath)
                setInputSurface(surface)
                prepare()
                start()
            }

            isRecording = true

            // Auto-stop after duration
            handler.postDelayed({
                stopRecording()
            }, VIDEO_DURATION_MS)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Live Photo recording", e)
            isRecording = false
        }
    }

    /**
     * Stop recording and save the video.
     */
    fun stopRecording() {
        if (!isRecording) return

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recorder", e)
        }

        mediaRecorder = null
        isRecording = false

        currentVideoFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                onVideoSaved?.invoke(file)
            }
        }
    }

    /**
     * Release resources.
     */
    fun release() {
        stopRecording()
        handlerThread.quitSafely()
    }

    fun isRecording(): Boolean = isRecording
}
