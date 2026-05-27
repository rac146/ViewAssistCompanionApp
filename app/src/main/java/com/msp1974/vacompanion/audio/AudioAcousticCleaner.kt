package com.msp1974.vacompanion.audio

import kotlin.math.cos
import kotlin.math.sin

class AudioAcousticCleaner(
    private val sampleRate: Int = VACAAudioFormat.SAMPLE_RATE_HZ,
    cutoffHz: Float = 300f,         // Aggressive high-pass to clear TV mud
    private val reverbDecay: Float = 0.85f // Decay factor (0.7 - 0.95). Higher = more aggressive dereverb
) {
    // HPF State Variables
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f

    // Dereverb State Variables (Spectral Envelope Follower)
    private var averageEnergy = 0f

    init {
        // Initialize Butterworth 2nd Order High Pass Filter coefficients
        val omega = (2.0 * Math.PI * cutoffHz / sampleRate).toFloat()
        val cosOmega = cos(omega.toDouble()).toFloat()
        val alpha = (sin(omega.toDouble()) / (2.0 * 0.707)).toFloat()

        val boost = 1.0f + alpha
        b0 = ((1.0f + cosOmega) / 2.0f) / boost
        b1 = (-(1.0f + cosOmega)) / boost
        b2 = ((1.0f + cosOmega) / 2.0f) / boost
        a1 = (-2.0f * cosOmega) / boost
        a2 = (1.0f - alpha) / boost
    }

    /**
     * Cleans the buffer in-place: High Pass -> Blind Dereverberation
     */
    fun clean(buffer: FloatArray) {
        for (i in buffer.indices) {
            val x0 = buffer[i]

            // --- STEP 1: High Pass Filter ---
            val hpfOut = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0; y2 = y1; y1 = hpfOut

            // --- STEP 2: Blind Spectral Dereverberation ---
            // Track the historical running envelope of room echo (the background smear)
            val absoluteSample = kotlin.math.abs(hpfOut)
            averageEnergy = (reverbDecay * averageEnergy) + ((1f - reverbDecay) * absoluteSample)

            // If the current sample is weaker than the room's lingering decay tail,
            // suppress it. This clips off the muddy TV echo reflections.
            var cleanSample = hpfOut
            if (absoluteSample < averageEnergy) {
                // Attenuate trailing smear heavily by pushing it toward silence
                cleanSample *= 0.15f
            }

            buffer[i] = cleanSample
        }
    }

    fun clean1(buffer: FloatArray) {
        for (i in buffer.indices) {
            val x0 = buffer[i]

            // Step 1: High Pass Filter (Gentle rumble removal)
            val hpfOut = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0; y2 = y1; y1 = hpfOut

            // Step 2: Adaptive Noise Floor (Soft spectral subtraction)
            val absoluteSample = kotlin.math.abs(hpfOut)
            averageEnergy = (reverbDecay * averageEnergy) + ((1f - reverbDecay) * absoluteSample)

            var cleanSample = hpfOut
            if (absoluteSample < averageEnergy) {
                // Soft reduction (0.60x) instead of hard muting.
                // This preserves background vocal shapes for conversational audio.
                cleanSample *= 0.60f
            }

            buffer[i] = cleanSample
        }
    }
}
