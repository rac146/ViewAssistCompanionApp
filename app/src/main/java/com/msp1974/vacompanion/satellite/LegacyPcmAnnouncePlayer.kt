package com.msp1974.vacompanion.satellite

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Legacy 0.10-style PCM player used for standalone announce playback.
 * Fixed format: 22050Hz / 16-bit / mono.
 */
class LegacyPcmAnnouncePlayer {
    private var audioTrack: AudioTrack? = null
    private var playing: Boolean = false

    private val sampleRate = 22050
    private val channels = AudioFormat.CHANNEL_OUT_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    @Synchronized
    fun play() {
        if (playing) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channels)
            .setEncoding(encoding)
            .build()

        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channels, encoding)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer)
            .build()

        audioTrack?.play()
        playing = true
    }

    @Synchronized
    fun writeAudio(buffer: ByteArray) {
        if (!playing) return
        audioTrack?.write(buffer, 0, buffer.size)
    }

    @Synchronized
    fun stop(force: Boolean = false) {
        val track = audioTrack ?: return
        try {
            if (force) {
                track.pause()
            } else {
                track.stop()
            }
        } catch (_: Exception) {
        }
        try {
            track.flush()
        } catch (_: Exception) {
        }
        try {
            track.release()
        } catch (_: Exception) {
        }
        audioTrack = null
        playing = false
    }

    @Synchronized
    fun isPlaying(): Boolean = playing
}

