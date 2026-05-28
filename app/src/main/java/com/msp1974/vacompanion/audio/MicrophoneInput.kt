package com.msp1974.vacompanion.audio

import android.Manifest
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.annotation.RequiresPermission
import com.msp1974.vacompanion.device.FunctionClasses
import com.msp1974.vacompanion.device.UnsupportedFunctionsDevice
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MicrophoneInput (
    val config: APPConfig,
    val audioSource: Int = VACAAudioFormat.DEFAULT_AUDIO_SOURCE,
    val sampleRateInHz: Int = VACAAudioFormat.SAMPLE_RATE_HZ,
    val channelConfig: Int = VACAAudioFormat.CHANNELS,
    val audioFormat: Int = VACAAudioFormat.ENCODING,
    val frameSize: Int = 0,
) : AutoCloseable {
    private var audioRecord: AudioRecord? = null
    private var webRtcApmProcessor: WebRtcApmProcessor? = null

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    private var audioDSP = AudioDSP()

    private val bufferSize =
        AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)

    val isRecording get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
    val speex = SpeexProcessor(sampleRate = sampleRateInHz, frameSize = if (frameSize > 0) frameSize else bufferSize )

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (audioRecord == null) {
            audioRecord = createAudioRecord()
            setupAudioEffects()
            if (useWebRtcApmBackend()) {
                webRtcApmProcessor = WebRtcApmProcessor(
                    enabled = true,
                    sampleRateHz = sampleRateInHz,
                    channels = 1,
                    suppressionLevel = 3,
                    postGain = resolveWebRtcPostGain(),
                    vadEnabled = true,
                    vadMode = 3
                )
            }
        }

        if (!isRecording) {
            Timber.d(
                "Starting microphone source=%d backend=%s webrtc=%s AGC=%s AEC=%s NS=%s",
                audioSource,
                config.experimentalAudioBackend,
                useWebRtcApmBackend(),
                agc != null,
                aec != null,
                ns != null
            )
            audioRecord?.startRecording()
        } else {
            Timber.w("Microphone already started")
        }
    }

    fun readBytes(): ByteBuffer {
        val audioShortBuffer = readShort(bufferSize)
        val buffer = ByteBuffer.allocateDirect(audioShortBuffer.size * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(audioShortBuffer)
        buffer.rewind()
        return buffer
    }

    fun readShort(bufferSize: Int = VACAAudioFormat.DEFAULT_BUFFER_SIZE_IN_SHORTS, useSpeex: Boolean = true): ShortArray {
        val audioBuffer = ShortArray(bufferSize)
        val audioRecord = this.audioRecord ?: error("Microphone not started")
        val readCount = audioRecord.read(audioBuffer, 0, audioBuffer.size)
        if (readCount > 0) {
            val raw = audioBuffer.copyOfRange(0, readCount)
            if (useWebRtcApmBackend()) {
                return webRtcApmProcessor?.process(raw) ?: raw
            }
            if (useSpeex && !AutomaticGainControl.isAvailable()) {
                speex.echoSuppressionEnabled = false
                speex.denoiseEnabled = false
                speex.setMaxAGCGain(20f + (config.micGain * 1.95f))
                return speex.processFrame(raw)
            }
            return raw
        }
        return ShortArray(0)
    }

    private fun useWebRtcApmBackend(): Boolean {
        return config.experimentalAudioBackend.equals(APPConfig.AUDIO_BACKEND_WEBRTC_APM, ignoreCase = true)
    }

    private fun resolveWebRtcPostGain(): Float {
        // Reuse existing mic gain control to provide makeup gain after WebRTC NS.
        // Keeps the same user tuning surface while preventing extreme clipping.
        //return (1.5f + (config.micGain.coerceAtLeast(0) * 0.45f)).coerceIn(1.5f, 6.0f)
        return (8.0f + (config.micGain.coerceAtLeast(0) * 1.2f)).coerceIn(8.0f, 24.0f)
    }

    fun readFloat(bufferSize: Int = VACAAudioFormat.DEFAULT_BUFFER_SIZE_IN_SHORTS): FloatArray {
        val audioBuffer = readShort(bufferSize)

        if (audioBuffer.isNotEmpty()) {
            return audioDSP.normaliseAudioBuffer(audioBuffer)
        }
        return FloatArray(0)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createAudioRecord(): AudioRecord {
        val audioRecord = AudioRecord(
            audioSource,
            sampleRateInHz,
            channelConfig,
            audioFormat,
            bufferSize * 2
        )
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "Failed to initialize AudioRecord"
        }
        return audioRecord
    }

    private fun setupAudioEffects() {
        val sessionId = audioRecord?.audioSessionId ?: return

        // Catch if issue with audio enhancements and do not load them
        if (UnsupportedFunctionsDevice.isIssueDevice(FunctionClasses.AUDIO_ENHANCEMENTS)) return

        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)
                agc?.enabled = true
            }
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)
                aec?.enabled = true
            }

            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)
                ns?.enabled = true
            }
        } catch (e: Exception) {}
    }

    override fun close() {

        agc?.release()
        agc = null

        aec?.release()
        aec = null

        ns?.release()
        ns = null

        webRtcApmProcessor?.close()
        webRtcApmProcessor = null

        audioRecord?.let {
            if (isRecording) {
                it.stop()
            }
            it.release()
            audioRecord = null
        }
    }
}
