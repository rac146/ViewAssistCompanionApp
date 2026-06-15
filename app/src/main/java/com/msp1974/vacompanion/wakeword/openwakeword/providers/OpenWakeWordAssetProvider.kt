package com.msp1974.vacompanion.wakeword.openwakeword.providers

import android.content.res.AssetManager
import com.msp1974.vacompanion.utils.Helpers.Companion.capitalizeWords
import com.msp1974.vacompanion.wakeword.WakeWordProvider
import com.msp1974.vacompanion.wakeword.models.Micro
import com.msp1974.vacompanion.wakeword.models.WakeWord
import com.msp1974.vacompanion.wakeword.models.WakeWordWithId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import timber.log.Timber
import java.io.FileInputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessMode

@OptIn(ExperimentalSerializationApi::class)
class OpenWakeWordAssetProvider(
    private val assets: AssetManager,
    private val path: String = DEFAULT_WAKE_WORD_PATH,
    private val extension: String = ONNX_EXT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : WakeWordProvider {
    override suspend fun get(): List<WakeWordWithId> = withContext(dispatcher) {
        val assetsList = assets.list(path) ?: return@withContext emptyList()
        val wakeWords = buildList {
            for (model in assetsList.filter { it.endsWith(extension) }) {
                runCatching {
                    val id = model.substring(0, model.lastIndexOf(extension))
                    val wakeWord = WakeWord(
                        type = "open",
                        wake_word = id.replace("_", " ").capitalizeWords(),
                        model = model,
                        micro = empty_micro
                    )

                    add(WakeWordWithId(id, wakeWord) { loadModel(wakeWord.model) })
                }.onFailure {
                    Timber.e(it, "Error loading wake word: $model")
                }
            }
        }
        return@withContext wakeWords
    }

    private suspend fun loadModel(model: String): ByteBuffer = withContext(dispatcher) {
        Timber.d("LOAD MODEL: $path/$model")
        try {
            assets.open("$path/$model").use { inputStream ->
                val bytes = inputStream.readBytes()
                return@use ByteBuffer.wrap(bytes)
            }
        } catch (e: Exception) {
            throw RuntimeException("Error loading file: $e")
        }
    }

    companion object {
        const val DEFAULT_WAKE_WORD_PATH = "openwakeword/wakeWords"
        const val ONNX_EXT = ".onnx"
        const val TFLITE_EXT = ".tflite"

        val empty_micro = Micro(
            probability_cutoff = 0f,
            sliding_window_size = 0
        )

    }
}