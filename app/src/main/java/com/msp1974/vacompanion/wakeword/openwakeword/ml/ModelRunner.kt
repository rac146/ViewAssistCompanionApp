package com.msp1974.vacompanion.wakeword.openwakeword.ml

import com.msp1974.vacompanion.wakeword.models.WakeWordWithId

interface ModelRunner: AutoCloseable {

    suspend fun loadModel(wakeWord: WakeWordWithId): ByteArray
    fun predictWakeWord(inputArray: Array<Array<FloatArray>>): Float
    override fun close()
}