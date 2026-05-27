package com.msp1974.vacompanion.audio

import android.content.Context
import com.kaleyra.androiddeepfilternet.filter.NativeDeepFilterNetLoader
import com.kaleyra.noise_filter.DeepFilterNet
import com.rikorose.deepfilternet.NativeDeepFilterNet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import timber.log.Timber

class DeepFilterNetProcessor(
    private val context: Context,
    private val enabled: Boolean,
    private val sampleRateHz: Int, // e.g., 16000
    private val attenuationDb: Float = 30f,
    private val postGain: Float = 1.8f
) : AutoCloseable {

    private var nativeEngine: DeepFilterNet? = null
    private var initialized = false
    private var failed = false

    private var frameProcessingBuffer: ByteBuffer? = null
    private var frameLengthBytes = 0

    // Reusable ByteArrays to avoid object allocation in the hot path
    private var raw48kBytes = ByteArray(0)
    private var clean48kBytes = ByteArray(0)

    private var temp48kShorts = ShortArray(0)
    private var clean16kShorts = ShortArray(0)

    fun isActive(): Boolean = enabled && !failed

    suspend fun process(samples: ShortArray): ShortArray {
        if (!enabled || failed || samples.isEmpty()) return samples
        if (!ensureInitialized()) return samples
        val engine = nativeEngine ?: return samples
        val directBuffer = frameProcessingBuffer ?: return samples

        val gain = postGain.coerceIn(0.5f, 24.0f)

        try {
            // 1. Resample input from 16kHz to 48kHz
            val upsampleFactor = 48000.0 / sampleRateHz.toDouble()
            val target48kShortsSize = (samples.size * upsampleFactor).roundToInt()

            if (temp48kShorts.size != target48kShortsSize) {
                temp48kShorts = ShortArray(target48kShortsSize)
            }
            if (clean16kShorts.size != samples.size) {
                clean16kShorts = ShortArray(samples.size)
            }

            performLinearResample(samples, temp48kShorts, sampleRateHz, 48000)

            // 2. Convert 48kHz ShortArray into raw PCM ByteArrays (1 short = 2 bytes)
            val target48kBytesSize = target48kShortsSize * 2
            if (raw48kBytes.size != target48kBytesSize) {
                raw48kBytes = ByteArray(target48kBytesSize)
                clean48kBytes = ByteArray(target48kBytesSize)
            }

            ByteBuffer.wrap(raw48kBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(temp48kShorts)

            // Standard Little-Endian short-to-byte conversion
//            for (i in temp48kShorts.indices) {
//                val s = temp48kShorts[i].toInt()
//                raw48kBytes[i * 2] = (s and 0xFF).toByte()
//                raw48kBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
//            }

            // 3. Process the ByteArray using Kaleyra's exact chunk loop strategy
            var srcOffset = 0
            while (srcOffset < raw48kBytes.size) {
                val remainingBytes = raw48kBytes.size - srcOffset
                val currentChunkSize = minOf(frameLengthBytes, remainingBytes)

                directBuffer.clear() // Reset position to 0

                // Load bytes into the shared memory window
                directBuffer.put(raw48kBytes, srcOffset, currentChunkSize)

                // Zero-pad trailing frame fragments if necessary
                if (currentChunkSize < frameLengthBytes) {
                    for (i in currentChunkSize until frameLengthBytes) {
                        directBuffer.put(0)
                    }
                }

                // CRITICAL NATIVE STEP 1: Flip playhead to 0 so native code can read it
                directBuffer.flip()

                // Process frame in-place via Rust engine
                engine.processFrame(directBuffer)

                // CRITICAL NATIVE STEP 2: Rewind playhead to 0 so Kotlin can read it
                directBuffer.rewind()

                // Extract processed bytes out of the shared window
                directBuffer.get(clean48kBytes, srcOffset, currentChunkSize)

                srcOffset += frameLengthBytes
            }

            ByteBuffer.wrap(clean48kBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(temp48kShorts)

            // 4. Convert the clean BytePack back into a 48kHz ShortArray
//            for (i in temp48kShorts.indices) {
//                val b1 = clean48kBytes[i * 2].toInt() and 0xFF
//                val b2 = clean48kBytes[i * 2 + 1].toInt()
//                temp48kShorts[i] = ((b2 shl 8) or b1).toShort()
//            }

            // 5. Downsample back down from 48kHz to 16kHz
           // clean16kShorts = ShortArray(samples.size)
            performLinearResample(temp48kShorts, clean16kShorts, 48000, sampleRateHz)

            // 6. Apply post-gain profile back to your source container
            for (i in samples.indices) {
                val value = (clean16kShorts[i].toFloat() * gain)
                    .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                samples[i] = value.roundToInt().toShort()
            }

            return samples
        } catch (t: Throwable) {
            failed = true
            Timber.w(t, "DeepFilterNet JNI transaction failed; skipping noise step.")
            return samples
        }
    }

    private suspend fun ensureInitialized(): Boolean {
        if (initialized) return true
        if (failed || !enabled) return false
        return try {
            // Note: Update this initialization call if your 'DeepFilterNetLoader' setup requires it
            val loader = NativeDeepFilterNetLoader(context)
            val engine = loader.loadDeepFilterNet()

            engine.setAttenuationLimit(attenuationDb)

            frameLengthBytes = engine.frameLength.toInt()

            // Allocate long-lived direct buffer using the exact byte configuration from the example
            frameProcessingBuffer = ByteBuffer.allocateDirect(frameLengthBytes).apply {
                order(ByteOrder.LITTLE_ENDIAN)
            }

            nativeEngine = engine
            initialized = true
            initialized
        } catch (t: Throwable) {
            failed = true
            Timber.w(t, "DeepFilterNet initialization failed.")
            false
        }
    }

    private fun performLinearResample(src: ShortArray, dst: ShortArray, fromRate: Int, toRate: Int) {
        val srcLength = src.size
        val dstLength = dst.size
        if (srcLength == 0 || dstLength == 0) return

        // Safe, overflow-proof floating point scaling factor
        val factor = fromRate.toFloat() / toRate.toFloat()

        for (i in 0 until dstLength) {
            val srcIndex = i * factor
            val index = srcIndex.toInt()
            val fraction = srcIndex - index

            if (index >= srcLength - 1) {
                dst[i] = src[srcLength - 1]
            } else {
                val s1 = src[index].toInt()
                val s2 = src[index + 1].toInt()
                // Highly optimized linear interpolation
                dst[i] = (s1 + fraction * (s2 - s1)).toInt().toShort()
            }
        }
    }

    override fun close() {
        if (nativeEngine != null) {
            runCatching { nativeEngine?.release() }
        }
        nativeEngine = null
        frameProcessingBuffer = null
        initialized = false
        raw48kBytes = ByteArray(0)
        clean48kBytes = ByteArray(0)
    }
}
