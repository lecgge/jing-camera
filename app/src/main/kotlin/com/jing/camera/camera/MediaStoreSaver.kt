package com.jing.camera.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves captured media into the shared DCIM/Jing collection so it shows up in the system gallery.
 */
object MediaStoreSaver {
    private const val CAMERA_RELATIVE_PATH = "DCIM/Jing"

    private fun timestamp(): String =
        SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US).format(Date())

    /**
     * Converts a JPEG Image to a ByteArray.
     */
    fun imageToByteArray(image: Image): ByteArray {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        image.close()
        return bytes
    }

    /**
     * Decodes a JPEG byte array into a Bitmap with correct orientation.
     */
    fun decodeByteArray(bytes: ByteArray, sensorOrientation: Int, mirror: Boolean = false): Bitmap {
        val original = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (sensorOrientation == 0 && !mirror) return original

        val matrix = Matrix().apply {
            postRotate(sensorOrientation.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            .also { if (it !== original) original.recycle() }
    }

    /**
     * Saves JPEG byte array to DCIM/Jing.
     */
    suspend fun saveJpeg(
        context: Context,
        bytes: ByteArray,
        displayName: String = "IMG_${timestamp()}",
        quality: Int = 95,
    ): Uri? = withContext(Dispatchers.IO) {
        saveBitmap(context, decodeByteArray(bytes, 0), displayName, quality)
    }

    /**
     * Saves a Bitmap to DCIM/Jing and returns its Uri, or null on failure.
     */
    suspend fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        displayName: String = "IMG_${timestamp()}",
        quality: Int = 95,
    ): Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, CAMERA_RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext null
            runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }.onFailure {
                resolver.delete(uri, null, null)
                return@withContext null
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Jing")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "$displayName.jpg")
            runCatching {
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                }
            }.getOrElse { return@withContext null }
            scanFile(context, file, "image/jpeg")
            Uri.fromFile(file)
        }
    }

    /**
     * Notifies the media scanner about a file.
     */
    fun scanFile(context: Context, file: File, mimeType: String) {
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(mimeType),
            null,
        )
    }

    /**
     * Gets the most recently saved photo URI for thumbnail display.
     */
    suspend fun getLatestPhoto(context: Context): Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val id = cursor.getLong(idColumn)
                    Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                } else null
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Jing")
            val files = dir.listFiles()?.filter { it.extension == "jpg" }?.sortedByDescending { it.lastModified() }
            files?.firstOrNull()?.let { Uri.fromFile(it) }
        }
    }
}
