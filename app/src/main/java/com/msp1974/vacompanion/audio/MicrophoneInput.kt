package com.msp1974.vacompanion.audio

import android.Manifest
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.io.FileOutputStream
import java.io.DataOutputStream

class MicrophoneInput (
    val config: APPConfig,
    val audioSource: Int = VACAAudioFormat.DEFAULT_AUDIO_SOURCE,
    val sampleRateInHz: Int = VACAAudioFormat.SAMPLE_RATE_HZ,
    val channelConfig: Int = VACAAudioFormat.CHANNELS,
    val audioFormat: Int = VACAAudioFormat.ENCODING,
    val frameSize: Int = 0,
) : AutoCloseable {
    // Standalone offline test: capture 10s raw audio, then post-process with RNNoise/Audx.
    // Enable only for manual experiments.
    private val debugOfflineRnNoiseTestEnabled = false
    private val debugOfflineRnNoiseDurationSec = 10

    private val debugCaptureEnabled = false
    private val debugCaptureDurationSec = 30
    private var debugCaptureEndMs = 0L
    private var debugCapturePath: String? = null
    private var debugCaptureStream: FileOutputStream? = null
    private var debugCaptureBytes = ByteArray(0)

    // Temporary experiment override: force RNNoise backend active regardless of HA/app config.
    private val forceRnNoiseBackend = false

    private var audioRecord: AudioRecord? = null

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    private var audioDSP = AudioDSP()

    private val bufferSize =
        AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)

    val isRecording get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
    val speex = SpeexProcessor(sampleRate = sampleRateInHz, frameSize = if (frameSize > 0) frameSize else bufferSize )
    private val webrtcApm = WebRtcApmProcessor(
        enabled = isWebRtcApmBackend(),
        sampleRateHz = sampleRateInHz,
        postGain = resolveWebRtcPostGain()
    )
    private val rnNoise = RnNoiseProcessor(
        enabled = isRnNoiseBackend(),
        sampleRateHz = sampleRateInHz,
        vadThreshold = config.experimentalRnNoiseVadThreshold,
        postGain = resolveRnNoisePostGain()
    )

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (debugOfflineRnNoiseTestEnabled) {
            Thread {
                runCatching { runOfflineRnNoiseTest(debugOfflineRnNoiseDurationSec) }
                    .onFailure {
                        Timber.w(it, "Offline RNNoise test failed")
                        BroadcastSender.sendBroadcast(
                            config.context,
                            BroadcastSender.TOAST_MESSAGE,
                            "RNNoise test failed: ${it.message ?: "unknown error"}"
                        )
                    }
            }.start()
            return
        }

        if (audioRecord == null) {
            audioRecord = createAudioRecord()
            setupAudioEffects()
        }

        if (!isRecording) {
            startDebugCaptureIfEnabled()
            Timber.d(
                "Starting microphone source=${resolveAudioSource()} backend=${effectiveBackend()} configBackend=${config.experimentalAudioBackend} webrtc=${webrtcApm.isActive()} rnNoise=${rnNoise.isActive()} AGC=${agc != null} AEC=${aec != null} NS=${ns != null}"
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
            var processed = audioBuffer.copyOfRange(0, readCount)
//            if (isWebRtcApmBackend()) {
                processed = webrtcApm.process(processed)
//            } else if (isRnNoiseBackend()) {
//                processed = rnNoise.process(processed)
//            } else if (useSpeex) {
               // speex.echoSuppressionEnabled = false
              //  speex.denoiseEnabled = true
              //  speex.setMaxAGCGain(10f + (config.micGain * 1.95f))
              //  processed = speex.processFrame(processed)
            //}
            writeDebugCapture(processed)
            return processed
        }
        return ShortArray(0)
    }

    fun readFloat(bufferSize: Int = VACAAudioFormat.DEFAULT_BUFFER_SIZE_IN_SHORTS): FloatArray {
        val audioBuffer = readShort(bufferSize)

        if (audioBuffer.isNotEmpty()) {
            return audioDSP.normaliseAudioBuffer(audioBuffer)
        }
        return FloatArray(0)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun runOfflineRnNoiseTest(durationSec: Int) {
        BroadcastSender.sendBroadcast(config.context, BroadcastSender.DEBUG_AUDIO_CAPTURE_STARTED)
        val source = resolveAudioSource()
        val minBuffer = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
        require(minBuffer > 0) { "Invalid AudioRecord buffer size: $minBuffer" }

        val recorder = AudioRecord(
            source,
            sampleRateInHz,
            channelConfig,
            audioFormat,
            minBuffer * 2
        )
        require(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "Failed to initialize AudioRecord for offline RNNoise test"
        }

        setupAudioEffects()

        val readBuffer = ShortArray(minBuffer)
        var captured = ShortArray((sampleRateInHz * durationSec).coerceAtLeast(minBuffer))
        var writePos = 0
        val endMs = SystemClock.elapsedRealtime() + (durationSec * 1000L)

        try {
            recorder.startRecording()
            Timber.i(
                "Offline RNNoise test started: duration=%ds source=%d sampleRate=%d",
                durationSec,
                source,
                sampleRateInHz
            )
            while (SystemClock.elapsedRealtime() < endMs) {
                val read = recorder.read(readBuffer, 0, readBuffer.size)
                if (read <= 0) continue

                if (writePos + read > captured.size) {
                    val newSize = ((captured.size * 3) / 2 + read).coerceAtLeast(writePos + read)
                    captured = captured.copyOf(newSize)
                }
                System.arraycopy(readBuffer, 0, captured, writePos, read)
                writePos += read
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            BroadcastSender.sendBroadcast(config.context, BroadcastSender.DEBUG_AUDIO_CAPTURE_STOPPED)
        }

        if (writePos <= 0) {
            throw IllegalStateException("No audio samples captured in offline RNNoise test")
        }

        val rawSamples = captured.copyOf(writePos)

        speex.echoSuppressionEnabled = false
        speex.denoiseEnabled = false
        speex.setMaxAGCGain(10f + (config.micGain * 1.95f))
        val processed = speex.processFrame(rawSamples.copyOf())

        val processedSamples = rnNoise.process(processed)

        val targetDir = File("/sdcard/Download")
        val fallbackDir = config.context.getExternalFilesDir(null) ?: config.context.filesDir
        val outputDir = if (targetDir.exists() || targetDir.mkdirs()) targetDir else fallbackDir
        val timestamp = System.currentTimeMillis()
        val rawPath = File(outputDir, "vaca_raw_${timestamp}.wav").absolutePath
        val processedPath = File(outputDir, "vaca_rnnoise_${timestamp}.wav").absolutePath

        writePcm16Wav(rawPath, rawSamples, sampleRateInHz, 1)
        writePcm16Wav(processedPath, processedSamples, sampleRateInHz, 1)

        Timber.i("Offline RNNoise test finished: raw=%s rnnoise=%s samples=%d", rawPath, processedPath, writePos)
        BroadcastSender.sendBroadcast(
            config.context,
            BroadcastSender.TOAST_MESSAGE,
            "RNNoise test saved: ${File(processedPath).name}"
        )
    }

    private fun writePcm16Wav(path: String, samples: ShortArray, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate * channels * 2
        val dataSize = samples.size * 2
        DataOutputStream(FileOutputStream(path, false)).use { out ->
            // RIFF header
            out.writeBytes("RIFF")
            out.writeInt(Integer.reverseBytes(36 + dataSize))
            out.writeBytes("WAVE")

            // fmt chunk
            out.writeBytes("fmt ")
            out.writeInt(Integer.reverseBytes(16)) // PCM chunk size
            out.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // PCM format
            out.writeShort(java.lang.Short.reverseBytes(channels.toShort()).toInt())
            out.writeInt(Integer.reverseBytes(sampleRate))
            out.writeInt(Integer.reverseBytes(byteRate))
            out.writeShort(java.lang.Short.reverseBytes((channels * 2).toShort()).toInt()) // block align
            out.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt()) // bits per sample

            // data chunk
            out.writeBytes("data")
            out.writeInt(Integer.reverseBytes(dataSize))
            for (s in samples) {
                out.writeShort(java.lang.Short.reverseBytes(s).toInt())
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createAudioRecord(): AudioRecord {
        val source = resolveAudioSource()
        val audioRecord = AudioRecord(
            source,
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
        if (isRnNoiseBackend()) {
            // Keep RNNoise path isolated: avoid stacking Android hardware NS/AEC/AGC.
            agc = null
            aec = null
            ns = null
            return
        }
        val sessionId = audioRecord?.audioSessionId ?: return
        try {
            // AGC intentionally disabled for wake-word false-positive testing.
            // It can amplify background speech (e.g. TV dialog) and hurt precision.
            agc = null
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

    private fun resolveAudioSource(): Int {
        if (audioSource != VACAAudioFormat.DEFAULT_AUDIO_SOURCE) {
            return audioSource
        }
        return if (isPlatformDspBackend()) {
            VACAAudioFormat.DEFAULT_AUDIO_SOURCE
        } else {
            VACAAudioFormat.FALLBACK_AUDIO_SOURCE
        }
    }

    private fun isWebRtcApmBackend(): Boolean {
        if (forceRnNoiseBackend) return false
        return config.experimentalAudioBackend == APPConfig.AUDIO_BACKEND_WEBRTC_APM
    }

    private fun isRnNoiseBackend(): Boolean {
        if (forceRnNoiseBackend) return true
        return config.experimentalAudioBackend == APPConfig.AUDIO_BACKEND_RNNOISE
    }

    private fun isPlatformDspBackend(): Boolean {
        return config.experimentalAudioBackend == APPConfig.AUDIO_BACKEND_PLATFORM_DSP
    }

    private fun effectiveBackend(): String {
        return when {
            isRnNoiseBackend() -> APPConfig.AUDIO_BACKEND_RNNOISE
            isWebRtcApmBackend() -> APPConfig.AUDIO_BACKEND_WEBRTC_APM
            isPlatformDspBackend() -> APPConfig.AUDIO_BACKEND_PLATFORM_DSP
            else -> "speex"
        }
    }

    private fun resolveWebRtcPostGain(): Float {
        // Reuse existing mic gain control to provide makeup gain after WebRTC NS.
        // Keeps the same user tuning surface while preventing extreme clipping.
        //return (1.5f + (config.micGain.coerceAtLeast(0) * 0.45f)).coerceIn(1.5f, 6.0f)
        return (8.0f + (config.micGain.coerceAtLeast(0) * 1.2f)).coerceIn(8.0f, 24.0f)
    }

    private fun resolveRnNoisePostGain(): Float {
        // Aggressive test profile: RNNoise output is much quieter on this hardware,
        // so we apply substantial makeup gain after denoise for wake-word detectability.
        return (8.0f + (config.micGain.coerceAtLeast(0) * 1.2f)).coerceIn(8.0f, 24.0f)
    }

    private fun startDebugCaptureIfEnabled() {
        if (!debugCaptureEnabled || debugCaptureStream != null) return

        val targetDir = File("/sdcard/Download")
        val fallbackDir = config.context.getExternalFilesDir(null) ?: config.context.filesDir
        val outputDir = if (targetDir.exists() || targetDir.mkdirs()) targetDir else fallbackDir
        val outFile = File(outputDir, "vaca_processed_${System.currentTimeMillis()}.pcm")

        runCatching {
            debugCaptureStream = FileOutputStream(outFile, false)
            debugCaptureEndMs = SystemClock.elapsedRealtime() + (debugCaptureDurationSec * 1000L)
            debugCapturePath = outFile.absolutePath
            Timber.i(
                "Debug capture started: path=%s duration=%ds sampleRate=%d",
                debugCapturePath,
                debugCaptureDurationSec,
                sampleRateInHz
            )
            BroadcastSender.sendBroadcast(config.context, BroadcastSender.DEBUG_AUDIO_CAPTURE_STARTED)
        }.onFailure {
            debugCaptureStream = null
            debugCaptureEndMs = 0L
            debugCapturePath = null
            Timber.w(it, "Debug capture start failed")
        }
    }

    private fun writeDebugCapture(samples: ShortArray) {
        val stream = debugCaptureStream ?: return
        if (samples.isEmpty() || SystemClock.elapsedRealtime() >= debugCaptureEndMs) {
            stopDebugCapture()
            return
        }

        val toWriteSamples = samples.size
        val byteCount = toWriteSamples * 2
        if (debugCaptureBytes.size < byteCount) {
            debugCaptureBytes = ByteArray(byteCount)
        }

        var j = 0
        for (i in 0 until toWriteSamples) {
            val s = samples[i].toInt()
            debugCaptureBytes[j++] = (s and 0xFF).toByte()
            debugCaptureBytes[j++] = ((s ushr 8) and 0xFF).toByte()
        }

        runCatching {
            stream.write(debugCaptureBytes, 0, byteCount)
            if (SystemClock.elapsedRealtime() >= debugCaptureEndMs) {
                stopDebugCapture()
            }
        }.onFailure {
            Timber.w(it, "Debug capture write failed")
            stopDebugCapture()
        }
    }

    private fun stopDebugCapture() {
        val path = debugCapturePath
        runCatching { debugCaptureStream?.flush() }
        runCatching { debugCaptureStream?.close() }
        debugCaptureStream = null
        debugCaptureEndMs = 0L
        debugCapturePath = null
        if (path != null) {
            Timber.i("Debug capture finished: path=%s", path)
            BroadcastSender.sendBroadcast(config.context, BroadcastSender.DEBUG_AUDIO_CAPTURE_STOPPED)
        }
    }

    override fun close() {

        agc?.release()
        agc = null

        aec?.release()
        aec = null

        ns?.release()
        ns = null

        webrtcApm.close()
        rnNoise.close()
        stopDebugCapture()

        audioRecord?.let {
            if (isRecording) {
                it.stop()
            }
            it.release()
            audioRecord = null
        }
    }
}
