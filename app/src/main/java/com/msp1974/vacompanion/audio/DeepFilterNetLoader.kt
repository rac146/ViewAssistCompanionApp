package com.kaleyra.androiddeepfilternet.filter

import android.content.Context
import com.kaleyra.noise_filter.DeepFilterNet
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

interface DeepFilterNetLoader {
    suspend fun loadDeepFilterNet(): DeepFilterNet
}

class NativeDeepFilterNetLoader(private val context: Context) : DeepFilterNetLoader {
    override suspend fun loadDeepFilterNet(): DeepFilterNet {
        val nativeDeepFilterNet = com.rikorose.deepfilternet.NativeDeepFilterNet(context.applicationContext)
        return suspendCancellableCoroutine { continuation ->
            nativeDeepFilterNet.onModelLoaded { deepFilterNet ->
                continuation.resume(deepFilterNet)
            }
        }
    }
}