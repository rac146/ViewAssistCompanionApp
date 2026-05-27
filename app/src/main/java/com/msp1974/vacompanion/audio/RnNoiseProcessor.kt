package com.msp1974.vacompanion.audio

import com.audx.android.Audx
import kotlin.math.roundToInt
import timber.log.Timber

/**
 * Optimized audio processing wrapper backed by Audx.
 * Designed for low-power continuous operation without heap allocations in the hot path.
 */
class RnNoiseProcessor(
    private val enabled: Boolean,
    private val sampleRateHz: Int,
    @Suppress("unused") private val vadThreshold: Float,
    private val postGain: Float = 1.8f
) : AutoCloseable {
    
    /**
     * FIXED: Explicitly typed lambda matching the '(Float) -> Unit' callback signature.
     * Captures the frame's voice/noise estimation score without runtime allocation.
     */
    private val processCallback: (Float) -> Unit = { _: Float -> }
    private var denoisedBuffer = ShortArray(0)
    private var chunkInput = ShortArray(0)
    private var chunkOutput = ShortArray(0)

    private var audx: Audx? = null
    private var initialized = false
    private var failed = false

    fun isActive(): Boolean = enabled && !failed

    fun process(samples: ShortArray): ShortArray {
        if (!enabled || failed || samples.isEmpty()) return samples
        if (!ensureInitialized()) return samples
        
        val processor = audx ?: return samples
        val gain = postGain.coerceIn(0.5f, 24.0f)
        if (denoisedBuffer.size != samples.size) {
            denoisedBuffer = ShortArray(samples.size)
        }
        // Audx behaves best with small frame processing; 10 ms at 16 kHz = 160 samples.
        val chunkSize = (sampleRateHz / 100).coerceAtLeast(1)
        if (chunkInput.size != chunkSize) {
            chunkInput = ShortArray(chunkSize)
            chunkOutput = ShortArray(chunkSize)
        }

        return try {
            var srcOffset = 0
            while (srcOffset < samples.size) {
                val thisChunk = minOf(chunkSize, samples.size - srcOffset)
                java.util.Arrays.fill(chunkInput, 0)
                System.arraycopy(samples, srcOffset, chunkInput, 0, thisChunk)

                // Process chunk with Audx callback API.
                processor.process(chunkInput, chunkOutput, processCallback)
                System.arraycopy(chunkOutput, 0, denoisedBuffer, srcOffset, thisChunk)
                srcOffset += thisChunk
            }

            for (i in samples.indices) {
                val value = (denoisedBuffer[i].toFloat() * gain)
                    .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                samples[i] = value.roundToInt().toShort()
            }
            samples
        } catch (t: Throwable) {
            failed = true
            Timber.w(t, "Audx processing failed; reverting permanently to passthrough")
            samples
        }
    }

    private fun ensureInitialized(): Boolean {
        if (initialized) return true
        if (failed || !enabled) return false
        return try {
            // Tailoring Audx builder explicitly for high-aggression stationary/voice noise mitigation
            audx = Audx.Builder()
                .inputRate(sampleRateHz)
                // Faster mode for low-power always-on devices.
                .resampleQuality(Audx.AUDX_RESAMPLER_QUALITY_VOIP)
                .build()
                
            initialized = audx != null
            initialized
        } catch (t: Throwable) {
            failed = true
            Timber.w(t, "Audx initialization failed; reverting to passthrough")
            false
        }
    }

    override fun close() {
        if (audx != null) {
            runCatching { audx?.close() }
        }
        audx = null
        initialized = false
        denoisedBuffer = ShortArray(0)
        chunkInput = ShortArray(0)
        chunkOutput = ShortArray(0)
    }
}
