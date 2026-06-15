package com.msp1974.vacompanion.utils

import android.content.Context
import com.msp1974.vacompanion.data.AvailableAlarm
import com.msp1974.vacompanion.data.AvailableWakeSound
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Helpers.Companion.capitalizeWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

enum class WakeWordType {
    OPENWAKEWORD,
    MICROWAKEWORD
}

/**
 * Represents the status of a custom file download.
 */
sealed class DownloadStatus {
    data class Progress(val fileName: String, val progress: Int) : DownloadStatus()
    data class Success(val fileName: String, val filePath: String) : DownloadStatus()
    data class Error(val fileName: String, val message: String) : DownloadStatus()
}

/**
 * Utility class to download custom files (models, sounds, alarms) from a URL 
 * and store them in the app's internal storage.
 * Emits download status via Flow.
 */
class CustomFileDownloader(private val context: Context, val config: APPConfig) {

    private val client = OkHttpClient()

    companion object {
        const val CUSTOM_DIR = "custom"
        const val WAKEWORDS_DIR = "wakewords"
        const val SOUNDS_DIR = "wakeword_sounds"
        const val ALARMS_DIR = "alarms"
    }

    /**
     * Lists all downloaded wake word names for a specific type.
     */
    fun listCustomWakeWordModels(type: WakeWordType): List<String> {
        val dir = File("${context.filesDir}/$CUSTOM_DIR/$WAKEWORDS_DIR/${type.toString().lowercase()}")
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()?.map { it.nameWithoutExtension }?.distinct()?.sorted() ?: emptyList()
    }

    /**
     * Lists all files in a custom subdirectory (e.g., sounds or alarms).
     */
    fun listCustomFiles(subDir: String): List<String> {
        val dir = File("${context.filesDir}/$CUSTOM_DIR/$subDir")
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
    }

    /**
     * Lists all wake sounds in the custom sounds directory.
     */
    fun listAvailableCustomWakeSounds(): List<AvailableWakeSound> {
        val dir = File("${context.filesDir}/$CUSTOM_DIR/$SOUNDS_DIR")
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()?.map { file ->
            val id = file.nameWithoutExtension
            AvailableWakeSound(
                id = id,
                name = formatDisplayName(id),
                custom = true,
                filename = file.name
            )
        }?.sortedBy { it.name } ?: emptyList()
    }

    /**
     * Lists all alarm sounds in the custom alarms directory.
     */
    fun listAvailableCustomAlarms(): List<AvailableAlarm> {
        val dir = File("${context.filesDir}/$CUSTOM_DIR/$ALARMS_DIR")
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()?.map { file ->
            val id = file.nameWithoutExtension
            AvailableAlarm(
                id = id,
                name = formatDisplayName(id),
                custom = true,
                filename = file.name
            )
        }?.sortedBy { it.name } ?: emptyList()
    }

    private fun formatDisplayName(name: String): String {
        return name.replace("_", " ").lowercase()
            .replaceFirstChar(Char::titlecaseChar)
            .capitalizeWords()
    }

    /**
     * Deletes all files associated with a wake word model.
     */
    fun deleteWakeWordModel(type: WakeWordType, name: String): Boolean {
        val fileNameBase = name.split(".")[0]
        val files = when(type) {
            WakeWordType.MICROWAKEWORD -> listOf("$fileNameBase.json", "$fileNameBase.tflite")
            WakeWordType.OPENWAKEWORD -> listOf("$fileNameBase.onnx", "$fileNameBase.tflite")
        }
        var allDeleted = true
        for (file in files) {
            if (!deleteWakeWordFile(type, file)) {
                allDeleted = false
            }
        }
        return allDeleted
    }

    /**
     * Deletes a custom file from a specific subdirectory.
     */
    fun deleteCustomFile(subDir: String, fileName: String): Boolean {
        val file = File("${context.filesDir}/$CUSTOM_DIR/$subDir", fileName)
        return if (file.exists()) file.delete() else false
    }

    /**
     * Downloads specific files for a wake word model.
     * If [customExtensions] is provided, only those extensions will be downloaded.
     */
    fun downloadWakeWordModel(wakeWordType: WakeWordType, name: String, customExtensions: List<String>? = null): Flow<DownloadStatus> = flow {
        val fileNameBase = name.split(".")[0]
        val extensions = customExtensions ?: when(wakeWordType) {
            WakeWordType.MICROWAKEWORD -> listOf("json", "tflite")
            WakeWordType.OPENWAKEWORD -> listOf("onnx", "tflite")
        }

        val baseUrl = AuthUtils.getHAUrl(config, false)
        val urlBase = URL(URL(baseUrl), "vaca/$CUSTOM_DIR/${wakeWordType.toString().lowercase()}/")

        for (ext in extensions) {
            val file = "$fileNameBase.$ext"
            val fileUrl = URL(urlBase, file).toString()
            downloadFileGeneric(Path(context.filesDir.absolutePath, CUSTOM_DIR, WAKEWORDS_DIR, wakeWordType.toString().lowercase()), fileUrl, file).collect { status ->
                emit(status)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Downloads a custom file (sound or alarm).
     */
    fun downloadCustomFile(subDir: String, fileName: String): Flow<DownloadStatus> = flow {
        val baseUrl = AuthUtils.getHAUrl(config, false)
        val fileUrl = URL(URL(baseUrl), "vaca/$CUSTOM_DIR/$subDir/$fileName").toString()
        val targetDir = Path(context.filesDir.absolutePath, CUSTOM_DIR, subDir)
        
        downloadFileGeneric(targetDir, fileUrl, fileName).collect { status ->
            emit(status)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Generic download helper.
     */
    private fun downloadFileGeneric(targetDir: Path, url: String, fileName: String): Flow<DownloadStatus> = flow {
        if (!targetDir.exists()) {
            try {
                targetDir.createDirectories()
            } catch (e: Exception) {
                emit(DownloadStatus.Error(fileName, "Failed to create directory: $targetDir"))
                return@flow
            }
        }

        val targetFile = File(targetDir.toString(), fileName)
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(DownloadStatus.Error(fileName, "Download failed: HTTP ${response.code}"))
                    return@flow
                }

                val body = response.body
                val contentLength = body?.contentLength() ?: -1L
                body?.byteStream()?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytesRead: Long = 0
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            
                            if (contentLength > 0) {
                                val progress = ((totalBytesRead * 100) / contentLength).toInt()
                                emit(DownloadStatus.Progress(fileName, progress))
                            }
                        }
                    }
                }
                
                Timber.i("Successfully downloaded $fileName to $targetFile")
                emit(DownloadStatus.Success(fileName, targetFile.toString()))
            }
        } catch (e: IOException) {
            Timber.e(e, "Error downloading from $url")
            emit(DownloadStatus.Error(fileName, e.message ?: "Unknown I/O error"))
        }
    }

    /**
     * Returns the file for a previously downloaded wake word file.
     */
    fun getDownloadedWakeWordFile(type: WakeWordType, fileName: String): File? {
        val file = File("${context.filesDir}/$CUSTOM_DIR/$WAKEWORDS_DIR/${
            type.toString().lowercase()
        }", fileName)
        return if (file.exists()) file else null
    }

    fun wakeWordFileExists(type: WakeWordType, fileName: String): Boolean {
        val file = File("${context.filesDir}/$CUSTOM_DIR/$WAKEWORDS_DIR/${type.toString().lowercase()}", fileName)
        return file.exists()
    }
    
    /**
     * Deletes a downloaded wake word file.
     */
    fun deleteWakeWordFile(type: WakeWordType, fileName: String): Boolean {
        return getDownloadedWakeWordFile(type, fileName)?.delete() ?: false
    }

}
