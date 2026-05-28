package com.msp1974.vacompanion.audio

import timber.log.Timber
import kotlin.math.roundToInt

/**
 * WebRTC NS path for wake-word capture.
 *
 * Runs WebRTC NoiseSuppressor per 10 ms frame, with optional VAD-based
 * non-speech attenuation to reduce steady background speech bleed.
 */
class WebRtcApmProcessor(
    private val enabled: Boolean,
    private val sampleRateHz: Int,
    private val channels: Int = 1,
    private val suppressionLevel: Int = 3, // 0..3 (3 most aggressive)
    private val postGain: Float = 1.0f,
    private val vadEnabled: Boolean = true,
    private val vadMode: Int = 3,
    private val nonSpeechGain: Float = 0.45f,
    private val speechHangoverFrames: Int = 3,
) : AutoCloseable {
    private val native = NativeWebRtcApm()
    private val vadNative = NativeWebRtcVad()
    private var handle: Long = 0L
    private var vadHandle: Long = 0L
    private var initialized = false
    private var failed = false
    private var vadActive = false
    private var vadHangover = 0
    private val frameSamples: Int = (sampleRateHz / 100) * channels
    private val inputFrame: ShortArray = ShortArray(frameSamples.coerceAtLeast(1))
    private val outputFrame: ShortArray = ShortArray(frameSamples.coerceAtLeast(1))
    private val pendingFrame: ShortArray = ShortArray(frameSamples.coerceAtLeast(1))
    private var pendingSize: Int = 0

    fun isActive(): Boolean = enabled && !failed

    fun process(samples: ShortArray): ShortArray {
        if (!enabled || failed || samples.isEmpty()) return samples
        if (!ensureInitialized()) return samples
        if (frameSamples <= 0) return samples

        val output = ShortArray(samples.size)
        var sampleOffset = 0
        var outputOffset = 0

        if (pendingSize > 0) {
            val needed = frameSamples - pendingSize
            val take = minOf(needed, samples.size)
            System.arraycopy(pendingFrame, 0, inputFrame, 0, pendingSize)
            System.arraycopy(samples, 0, inputFrame, pendingSize, take)

            if (pendingSize + take == frameSamples) {
                if (!processOneFrame(inputFrame, outputFrame)) return samples
                val frameGain = resolveFrameGateGain(outputFrame)
                copyWithGain(outputFrame, pendingSize, output, 0, take, frameGain)
                outputOffset += take
                sampleOffset += take
                pendingSize = 0
            } else {
                System.arraycopy(samples, 0, output, 0, samples.size)
                System.arraycopy(samples, 0, pendingFrame, pendingSize, take)
                pendingSize += take
                return output
            }
        }

        while (sampleOffset + frameSamples <= samples.size) {
            System.arraycopy(samples, sampleOffset, inputFrame, 0, frameSamples)
            if (!processOneFrame(inputFrame, outputFrame)) return samples
            val frameGain = resolveFrameGateGain(outputFrame)
            copyWithGain(outputFrame, 0, output, outputOffset, frameSamples, frameGain)
            sampleOffset += frameSamples
            outputOffset += frameSamples
        }

        val remaining = samples.size - sampleOffset
        if (remaining > 0) {
            System.arraycopy(samples, sampleOffset, output, outputOffset, remaining)
            System.arraycopy(samples, sampleOffset, pendingFrame, 0, remaining)
            pendingSize = remaining
        }

        return output
    }

    private fun copyWithGain(
        src: ShortArray,
        srcOffset: Int,
        dst: ShortArray,
        dstOffset: Int,
        count: Int,
        frameGain: Float
    ) {
        val effectiveGain = (postGain * frameGain).coerceIn(0f, 12f)
        if (effectiveGain == 1.0f) {
            System.arraycopy(src, srcOffset, dst, dstOffset, count)
            return
        }
        for (i in 0 until count) {
            val v = (src[srcOffset + i].toFloat() * effectiveGain)
                .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
            dst[dstOffset + i] = v.roundToInt().toShort()
        }
    }

    private fun resolveFrameGateGain(frame: ShortArray): Float {
        if (!vadActive) return 1.0f
        return try {
            val vad = vadNative.nativeProcess(vadHandle, sampleRateHz, frame)
            if (vad < 0) {
                vadActive = false
                if (vadHandle != 0L) {
                    runCatching { vadNative.nativeDestroy(vadHandle) }
                    vadHandle = 0L
                }
                1.0f
            } else {
                val isSpeech = vad > 0
                if (isSpeech) vadHangover = speechHangoverFrames
                val inSpeechWindow = isSpeech || vadHangover > 0
                if (!isSpeech && vadHangover > 0) vadHangover--
                if (inSpeechWindow) 1.0f else nonSpeechGain
            }
        } catch (t: Throwable) {
            vadActive = false
            if (vadHandle != 0L) {
                runCatching { vadNative.nativeDestroy(vadHandle) }
                vadHandle = 0L
            }
            Timber.w(t, "WebRTC VAD stage failed; disabling gate")
            1.0f
        }
    }

    private fun processOneFrame(input: ShortArray, output: ShortArray): Boolean {
        return try {
            val processed = native.nativeProcess(handle, input, output)
            if (processed != frameSamples) {
                failed = true
                pendingSize = 0
                Timber.w(
                    "WebRTC APM processing failed code=%d expected=%d; passthrough",
                    processed,
                    frameSamples
                )
                false
            } else {
                true
            }
        } catch (t: Throwable) {
            failed = true
            pendingSize = 0
            Timber.w(t, "WebRTC APM processing failed; passthrough")
            false
        }
    }

    private fun ensureInitialized(): Boolean {
        if (initialized) return true
        if (failed || !enabled) return false
        return try {
            handle = native.nativeCreate(sampleRateHz, channels, suppressionLevel)
            initialized = handle != 0L
            if (!initialized) {
                failed = true
                Timber.w("WebRTC APM init failed; null handle")
            } else if (vadEnabled && channels == 1) {
                vadHandle = vadNative.nativeCreate(vadMode)
                vadActive = vadHandle != 0L
                if (!vadActive) {
                    Timber.w("WebRTC VAD gate init failed; continuing NS-only")
                }
            }
            initialized
        } catch (t: Throwable) {
            failed = true
            Timber.w(t, "WebRTC APM init failed; passthrough")
            false
        }
    }

    override fun close() {
        if (handle != 0L) runCatching { native.nativeDestroy(handle) }
        if (vadHandle != 0L) runCatching { vadNative.nativeDestroy(vadHandle) }
        handle = 0L
        vadHandle = 0L
        vadActive = false
        vadHangover = 0
        initialized = false
        pendingSize = 0
    }
}

