package com.msp1974.vacompanion.audio

import android.media.AudioFormat
import android.media.MediaRecorder

/**
 * Canonical audio format constants for the VACA capture/playback path.
 *
 * All components that produce or consume audio on the pipeline
 * (MicrophoneInput, VoicePlayer, WebRTC APM, SatelliteClientHandler, etc.)
 * must agree on these values. Centralising them here prevents silent mismatches
 * when one file is updated but others are not.
 */
object VACAAudioFormat {
    /** Sample rate in Hz. WebRTC APM requires 16 kHz. */
    const val SAMPLE_RATE_HZ = 16000

    /** Number of audio channels (mono). */
    const val CHANNELS = 1

    /** Bytes per sample (16-bit PCM = 2). */
    const val BYTES_PER_SAMPLE = 2

    /** Android AudioFormat encoding constant for 16-bit PCM. */
    @JvmField val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    /** Android AudioFormat channel config for mono input. */
    @JvmField val CHANNEL_IN_CONFIG = AudioFormat.CHANNEL_IN_MONO

    /** Samples per 10 ms frame (used by WebRTC APM). */
    const val FRAME_SIZE_10MS = SAMPLE_RATE_HZ / 100  // 160

    const val DEFAULT_AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
    const val FALLBACK_AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION

    const val DEFAULT_BUFFER_SIZE_IN_SHORTS = 1280
}
