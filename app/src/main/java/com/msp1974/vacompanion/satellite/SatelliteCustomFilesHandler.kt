package com.msp1974.vacompanion.satellite

import android.content.Context
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.utils.Helpers.Companion.capitalizeWords
import com.msp1974.vacompanion.utils.DownloadStatus
import com.msp1974.vacompanion.utils.CustomFileDownloader
import com.msp1974.vacompanion.utils.WakeWordType
import kotlinx.serialization.json.*
import timber.log.Timber

class SatelliteCustomFilesHandler(
    val context: Context,
    val config: APPConfig,
    val viewModel: VAViewModel? = null
) {
    private var customFileDownloader = CustomFileDownloader(context, config)

    suspend fun downloadAllCustomFiles(force: Boolean = false): Boolean {
        var hasDownloaded = false
        val customFiles = config.customFiles as? JsonObject ?: return false

        // Download Wake Words
        for (wakeWordTypeEntry in customFiles) {
            val typeKey = wakeWordTypeEntry.key
            
            val wakeWordModelType = if (typeKey == WakeWordType.MICROWAKEWORD.toString().lowercase()) {
                WakeWordType.MICROWAKEWORD 
            } else if (typeKey == WakeWordType.OPENWAKEWORD.toString().lowercase()) {
                WakeWordType.OPENWAKEWORD
            } else {
                continue
            }

            // Handle both JsonObject (map) and JsonArray (list)
            val wakeWordEntries = when (val value = wakeWordTypeEntry.value) {
                is JsonObject -> value.entries.map { it.key to (it.value as? JsonObject) }
                is JsonArray -> value.map { it.jsonPrimitive.content to null as JsonObject? }
                else -> continue
            }

            for ((name, entryConfig) in wakeWordEntries) {
                val configExtensions = entryConfig?.get("extensions")?.jsonArray?.map { it.jsonPrimitive.content }
                
                val extensions = configExtensions ?: when (wakeWordModelType) {
                    WakeWordType.MICROWAKEWORD -> listOf("json", "tflite")
                    WakeWordType.OPENWAKEWORD -> listOf("onnx", "tflite")
                }

                if (wakeWordModelType == WakeWordType.OPENWAKEWORD) {
                    val missingExtensions = mutableListOf<String>()
                    for (ext in extensions) {
                        if (force || !customFileDownloader.wakeWordFileExists(wakeWordModelType, "$name.$ext")) {
                            missingExtensions.add(ext)
                        }
                    }
                    
                    if (missingExtensions.isNotEmpty()) {
                        Timber.i("Download of $name ($wakeWordModelType) files needed: $missingExtensions")
                        val displayName = name.replace("_", " ").capitalizeWords()
                        customFileDownloader.downloadWakeWordModel(wakeWordModelType, name, missingExtensions).collect { status ->
                            handleDownloadStatus(displayName, status)
                        }
                        hasDownloaded = true
                    }
                } else {
                    if (!extensions.contains("json") || !extensions.contains("tflite")) {
                        Timber.w("Skipping microWakeWord $name: required extensions (json, tflite) not fully specified in config")
                        continue
                    }

                    var downloadNeeded = force
                    if (!downloadNeeded) {
                        for (ext in extensions) {
                            if (!customFileDownloader.wakeWordFileExists(wakeWordModelType, "$name.$ext")) {
                                downloadNeeded = true
                                break
                            }
                        }
                    }

                    if (downloadNeeded) {
                        Timber.i("Download of $name ($wakeWordModelType) needed")
                        val displayName = name.replace("_", " ").capitalizeWords()
                        customFileDownloader.downloadWakeWordModel(wakeWordModelType, name, extensions).collect { status ->
                            handleDownloadStatus(displayName, status)
                        }
                        hasDownloaded = true
                    }
                }
            }
        }

        // Download Sounds and Alarms
        if (downloadAllGenericFiles(customFiles, CustomFileDownloader.SOUNDS_DIR, force)) hasDownloaded = true
        if (downloadAllGenericFiles(customFiles, CustomFileDownloader.ALARMS_DIR, force)) hasDownloaded = true

        return hasDownloaded
    }

    private suspend fun downloadAllGenericFiles(customFiles: JsonObject, subDir: String, force: Boolean = false): Boolean {
        var hasDownloaded = false
        val files = customFiles[subDir] as? JsonObject ?: return false
        
        for (fileEntry in files) {
            val name = fileEntry.key
            val entryConfig = fileEntry.value as? JsonObject
            val extensions = entryConfig?.get("extensions")?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("xxx")

            for (ext in extensions) {
                val fileName = "$name.$ext"
                val fileExists = java.io.File("${context.filesDir}/${CustomFileDownloader.CUSTOM_DIR}/$subDir", fileName).exists()

                if (force || !fileExists) {
                    Timber.i("Download of $subDir/$fileName needed")
                    val displayName = name.replace("_", " ").capitalizeWords()
                    customFileDownloader.downloadCustomFile(subDir, fileName).collect { status ->
                        handleDownloadStatus(displayName, status)
                    }
                    hasDownloaded = true
                }
            }
        }
        return hasDownloaded
    }

    suspend fun syncAllCustomFiles() {
        val customFiles = config.customFiles as? JsonObject ?: return

        // 1. Sync Wake Words and Cleanup Orphans
        val configuredWakeWords = mutableMapOf<WakeWordType, Set<String>>()
        
        for (wakeWordTypeEntry in customFiles) {
            val typeKey = wakeWordTypeEntry.key
            if (typeKey == WakeWordType.MICROWAKEWORD.toString().lowercase() || typeKey == WakeWordType.OPENWAKEWORD.toString().lowercase()) {
                val type = if (typeKey == WakeWordType.MICROWAKEWORD.toString().lowercase()) WakeWordType.MICROWAKEWORD else WakeWordType.OPENWAKEWORD
                
                val names = when (val value = wakeWordTypeEntry.value) {
                    is JsonObject -> value.keys
                    is JsonArray -> value.map { it.jsonPrimitive.content }.toSet()
                    else -> emptySet()
                }
                configuredWakeWords[type] = names
            }
        }

        // Cleanup orphaned wake words
        WakeWordType.entries.forEach { type ->
            val localFiles = customFileDownloader.listCustomWakeWordModels(type)
            val configuredForType = configuredWakeWords[type] ?: emptySet()
            localFiles.forEach { localName ->
                if (!configuredForType.contains(localName)) {
                    Timber.i("Deleting orphaned wake word: $localName ($type)")
                    customFileDownloader.deleteWakeWordModel(type, localName)
                }
            }
        }

        // 2. Sync Sounds and Alarms
        // Cleanup orphans first
        cleanupAllGenericOrphans(customFiles, CustomFileDownloader.SOUNDS_DIR)
        cleanupAllGenericOrphans(customFiles, CustomFileDownloader.ALARMS_DIR)

        // Download/Update everything
        downloadAllCustomFiles(force = true)
    }

    private fun cleanupAllGenericOrphans(customFiles: JsonObject, subDir: String) {
        val configuredEntries = customFiles[subDir] as? JsonObject ?: return
        val configuredFiles = mutableSetOf<String>()
        
        for (entry in configuredEntries) {
            val name = entry.key
            val extensions = (entry.value as? JsonObject)?.get("extensions")?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("wav")
            extensions.forEach { ext -> configuredFiles.add("$name.$ext") }
        }

        val localFiles = customFileDownloader.listCustomFiles(subDir)

        localFiles.forEach { localName ->
            if (!configuredFiles.contains(localName)) {
                Timber.i("Deleting orphaned $subDir: $localName")
                customFileDownloader.deleteCustomFile(subDir, localName)
            }
        }
    }

    private fun handleDownloadStatus(displayName: String, status: DownloadStatus) {
        when (status) {
            is DownloadStatus.Progress -> viewModel?.setDownloadProgress(displayName, status.progress)
            is DownloadStatus.Success, is DownloadStatus.Error -> viewModel?.clearDownloadProgress()
        }
    }
}
