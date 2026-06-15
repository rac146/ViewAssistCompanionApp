package com.msp1974.vacompanion.utils

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

object FileDownloader {

    private val client = OkHttpClient()

    /**
     * Downloads a file from the given [url] and saves it to the public Downloads folder.
     * Supports Android 8 (API 26) through Android 16.
     *
     * @param context Application context
     * @param url The URL to download from
     * @param fileName The name to save the file as (e.g., "update.apk")
     * @return The Uri of the downloaded file, or null if the download failed.
     */
    suspend fun downloadFileToDownloads(
        context: Context,
        url: String,
        fileName: String
    ): Uri? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Timber.e("Failed to download file: ${response.code} ${response.message}")
                return@withContext null
            }

            val body = response.body
            val mimeType = response.header("Content-Type") ?: "application/octet-stream"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ (API 29+) using MediaStore (Scoped Storage)
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return@withContext null

                resolver.openOutputStream(uri)?.use { outputStream ->
                    body.byteStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                return@withContext uri
            } else {
                // Android 8-9 (API 26-28) using traditional File API
                // REQUIRES: <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
                @Suppress("DEPRECATION")
                val targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!targetDir.exists() && !targetDir.mkdirs()) {
                    Timber.e("Failed to create Downloads directory")
                    return@withContext null
                }

                val file = File(targetDir, fileName)
                FileOutputStream(file).use { outputStream ->
                    body.byteStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                // Scan the file so it shows up in the Downloads app immediately
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType), null)
                
                return@withContext Uri.fromFile(file)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error downloading file from $url")
            return@withContext null
        }
    }
}
