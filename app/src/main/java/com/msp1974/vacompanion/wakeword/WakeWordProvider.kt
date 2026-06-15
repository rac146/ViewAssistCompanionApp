package com.msp1974.vacompanion.wakeword

import com.msp1974.vacompanion.wakeword.models.WakeWordWithId

interface WakeWordProvider {
    suspend fun get(): List<WakeWordWithId>
}


