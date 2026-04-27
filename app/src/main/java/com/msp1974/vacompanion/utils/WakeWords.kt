package com.msp1974.vacompanion.utils

import android.content.Context
import android.os.Environment
import com.msp1974.vacompanion.wakeword.WakeWord
import timber.log.Timber
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.forEachDirectoryEntry
import kotlin.io.path.isDirectory



class WakeWords(val context: Context) {
    var availableWakeWords = mapOf(
        "alexa" to WakeWord("Alexa", "openwakeword/alexa.onnx"),
        "hey_jarvis" to WakeWord("Hey Jarvis", "openwakeword/hey_jarvis.onnx"),
        "hey_mycroft" to WakeWord("Hey Mycroft", "openwakeword/hey_mycroft.onnx"),
        "hey_raspy" to WakeWord("Hey Rhasspy", "openwakeword/hey_rhasspy.onnx"),
        "ok_nabu" to WakeWord("Ok Nabu", "openwakeword/ok_nabu.onnx"),
        "ok_computer" to WakeWord("Ok Computer", "openwakeword/ok_computer.onnx")
    )

    fun getCustomWakeWords(path: String): Map<String, WakeWord> {
        val customWakeWords = mutableMapOf<String, WakeWord>()
        val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val vacaDownloadDir = Path(downloadPath.toString(), path)
        val vacaFilesDir = Path(context.filesDir.toString(), path)

        if (vacaDownloadDir.isDirectory()) {
            Timber.d("Custom wake words directory found in Downloads - ${vacaDownloadDir.toFile().absolutePath}")
            vacaDownloadDir.forEachDirectoryEntry( "*.onnx", { entry ->
                Timber.d("Found custom wake word: ${entry.fileName}")
                val key = entry.fileName.toString().replace(".onnx", "").lowercase()
                val name = key.replace("_", " ")

                customWakeWords[key] = WakeWord(name.capitalizeWords(), entry.absolutePathString(), false)
            })
        }

        if (vacaFilesDir.isDirectory()) {
            Timber.d("Custom wake words directory found in App files - ${vacaFilesDir.toFile().absolutePath}")
            vacaFilesDir.forEachDirectoryEntry( "*.onnx", { entry ->
                Timber.d("Found custom wake word: ${entry.fileName}")
                val key = entry.fileName.toString().replace(".onnx", "").lowercase()
                val name = key.replace("_", " ")

                customWakeWords[key] = WakeWord(name.capitalizeWords(), entry.absolutePathString(), false)
            })
        }
        return customWakeWords
    }

    fun String.capitalizeWords(delimiter: String = " ") =
        split(delimiter).joinToString(delimiter) { word ->

            val smallCaseWord = word.lowercase()
            smallCaseWord.replaceFirstChar(Char::titlecaseChar)

        }

    fun getWakeWords(): Map<String, WakeWord> {
        return  mutableMapOf<String, WakeWord>().apply {
            putAll(availableWakeWords)
            putAll(getCustomWakeWords("vaca"))
        }
    }
}