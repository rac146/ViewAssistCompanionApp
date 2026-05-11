package com.msp1974.vacompanion.audio

import com.audx.android.Audx
import com.audx.android.AudxConfig
import timber.log.Timber

/**
 * Typed RNNoise wrapper (Audx).
 */
class RnNoiseProcessor(
    private val enabled: Boolean,
    private val sampleRateHz: Int,
    private val vadThreshold: Float
) : AutoCloseable {
    private val frameSize = Audx.FRAME_SIZE
    private val inFrame = ShortArray(frameSize)
    private val outFrame = ShortArray(frameSize)

    private var engine: Audx? = null
    private var initialized = false
    private var failed = false
    private var vadScore = 0f

    fun isActive(): Boolean = enabled && !failed

    fun process(samples: ShortArray): ShortArray {
        if (!enabled || failed || samples.isEmpty()) return samples
        if (!ensureInitialized()) return samples

        val result = ShortArray(samples.size)
        var srcOffset = 0
        var dstOffset = 0

        while (srcOffset < samples.size) {
            val chunk = minOf(frameSize, samples.size - srcOffset)
            java.util.Arrays.fill(inFrame, 0)
            System.arraycopy(samples, srcOffset, inFrame, 0, chunk)

            try {
                engine?.process(inFrame, outFrame) { score -> vadScore = score }
            } catch (t: Throwable) {
                failed = true
                Timber.w(t, "RNNoise processing failed; disabling")
                return samples
            }
            if (vadScore >= vadThreshold) {
                System.arraycopy(outFrame, 0, result, dstOffset, chunk)
            } else {
                // Keep low-level context but strongly attenuate non-speech frames.
                for (i in 0 until chunk) {
                    result[dstOffset + i] = (outFrame[i] * 0.2f).toInt().toShort()
                }
            }
            srcOffset += chunk
            dstOffset += chunk
        }
        return result
    }

    private fun ensureInitialized(): Boolean {
        if (initialized) return true
        if (failed || !enabled) return false

        return try {
            val config = AudxConfig(inputRate = sampleRateHz, resampleQuality = Audx.AUDX_RESAMPLER_QUALITY_VOIP)
            engine = Audx(config).also { it.create() }
            initialized = true
            Timber.i("RNNoise path enabled with Audx (vadThreshold=$vadThreshold)")
            true
        } catch (t: Throwable) {
            failed = true
            Timber.w(t, "RNNoise init failed; passthrough")
            false
        }
    }

    override fun close() {
        runCatching { engine?.close() }
        engine = null
    }
}
