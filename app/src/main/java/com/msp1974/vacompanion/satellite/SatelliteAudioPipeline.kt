package com.msp1974.vacompanion.satellite

import android.content.Context
import com.msp1974.vacompanion.satellite.Satellite.Companion.isoNow
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.wyoming.WyomingPacket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber

enum class PipelineStage {
    INIT,
    STARTING,
    STARTED,
    LISTENING,
    VOICE_STARTED,
    VOICE_STOPPED,
    AWAITING_RESPONSE,
    AWAITING_TTS,
    STREAMING_TTS,
    ENDED,
}

enum class PipelineEndReason {
    NONE,
    END_OF_PIPELINE,
    FORCE_STOPPED,
    TIMED_OUT,
    ERRORED,
    DUPLICATE_WAKEUP
}

interface IAudioPipeline {
    fun sendMessage(packet: WyomingPacket)
    fun onStateChange(state: PipelineStage)
    fun onFinish(reason: PipelineEndReason)
}

abstract class SatelliteAudioPipeline(
    val context: Context,
    val scope: CoroutineScope,
    val config: APPConfig,
    val pipelineId: Int,
    val mediaManager: SatelliteMediaManager,
    val isContinuation: Boolean = false
): IAudioPipeline {

    companion object {
        val CONTINUATION_STOP_WORDS = listOf("stop", "cancel", "never mind")
    }

    private var pipelineRunning = CompletableDeferred<PipelineEndReason>()
    private var audioMessageQueue = Channel<WyomingPacket>(capacity = 200)
    private var stageTimeoutJob: Job? = null
    private var result: PipelineEndReason = PipelineEndReason.NONE
    var pipelineStage = PipelineStage.INIT
        set(value) {
            field = value
            onStateChange(value)
        }

    fun run(sendDetection: Boolean = false) {
        scope.launch {
            start(sendDetection)
        }
    }


    suspend fun start(sendDetection: Boolean = false) {
        val job = scope.launch(Dispatchers.Default) {
            try {
                pipelineStage = PipelineStage.STARTING
                if (sendDetection) {
                    Timber.d("Sending detection event")
                    sendMessage(buildDetectionMessage())
                }
                Timber.d("Starting pipeline [$pipelineId]")
                sendMessage(buildRunPipelineMessage())
                // 0.10 compatibility: older integrations can miss/lag the initial `transcribe`
                // event. If that happens, start local streaming so audio still reaches HA.
                scope.launch {
                    delay(1500)
                    if (pipelineStage == PipelineStage.STARTED && !pipelineRunning.isCompleted) {
                        Timber.w("No transcribe event received, enabling compatibility listening mode")
                        handleTranscribe()
                    }
                }
                mediaManager.voicePlayer.start(22050,2,1)
                pipelineStage = PipelineStage.STARTED
                audioMessageHandler()
                awaitCancellation()
            } finally {
                //TODO: Change to audio stop
                withContext(NonCancellable) {
                    cancelStageTimeout()
                    val msg = when (result) {
                        PipelineEndReason.END_OF_PIPELINE -> { "Pipeline ended normally" }
                        PipelineEndReason.FORCE_STOPPED -> { "Pipeline was terminated" }
                        PipelineEndReason.ERRORED -> { "Pipeline ended with an error" }
                        PipelineEndReason.TIMED_OUT -> { "Pipeline timed out at stage: $pipelineStage" }
                        PipelineEndReason.DUPLICATE_WAKEUP -> { "Pipeline ended due to duplicate wake up" }
                        else -> { "Pipeline ended for unknown reason" }
                    }
                    Timber.d("$msg [$pipelineId]")
                    mediaManager.voicePlayer.stop()
                    onFinish(result)
                    pipelineStage = PipelineStage.ENDED
                }
            }
        }
        result = pipelineRunning.await()
        job.cancel()
        Timber.d("Pipeline stopped [$pipelineId] -> $result.")
    }

    fun stop() {
        cancelStageTimeout()
        if (pipelineStage != PipelineStage.ENDED) sendMessage(buildAudioStopMessage())
        pipelineRunning.complete(PipelineEndReason.FORCE_STOPPED)
    }

    suspend fun processAudioPipelineMessage(packet: WyomingPacket) {
        when (packet.type) {
            "transcribe" -> handleTranscribe()
            "voice-started" -> handleVoiceStarted()
            "voice-stopped" -> handleVoiceStopped()
            "transcript" -> handleTranscript(packet)
            "synthesize" -> handleSynthesize()
            "audio-start", "audio-chunk", "audio-stop" -> queueAudioMessage(packet)
            "pipeline-ended" -> handlePipelineEnded()
            "error" -> handlePipelineError(packet)
        }
    }

    private suspend fun queueAudioMessage(packet: WyomingPacket) {
        audioMessageQueue.send(packet)
    }

    private suspend fun audioMessageHandler() {
        val job = scope.launch(Dispatchers.Default) {
            Timber.d("Audio message handler started.")
            try {
                while (true) {
                    val msg = audioMessageQueue.receive()
                    when (msg.type) {
                        "audio-start" -> handleAudioStart(msg)
                        "audio-chunk" -> handleAudioChunk(msg)
                        "audio-stop" -> handleAudioStop()
                    }
                    yield()
                }
            } finally {
                withContext(NonCancellable) {
                    Timber.d("Ending audio message handler.")
                    audioMessageQueue.cancel()
                }
            }
        }
        result = pipelineRunning.await()
        job.cancel()
        Timber.d("Audio message handler stopped.")

    }

    fun sendMicAudio(audio: ByteArray): Boolean {
        if (pipelineStage == PipelineStage.LISTENING  || pipelineStage == PipelineStage.VOICE_STARTED) {
            val packet = buildAudioPacketMessage(audio)
            sendMessage(packet)
            return true
        }
        return false
    }

    internal fun handleTranscribe() {
        pipelineStage = PipelineStage.LISTENING
        setStageTimeout(seconds = 10, expected = "voice-started/transcript")
    }

    internal fun handleVoiceStarted() {
        pipelineStage = PipelineStage.VOICE_STARTED
        setStageTimeout(seconds = 30, expected = "voice-stopped/transcript")
    }

    internal fun handleVoiceStopped() {
        pipelineStage = PipelineStage.VOICE_STOPPED
        setStageTimeout(seconds = 15, expected = "transcript")
    }

    internal fun handleTranscript(packet: WyomingPacket) {
        // Handle pipeline cancel words
        if (isContinuation && packet.getProp("text").lowercase().replace(".", "") in CONTINUATION_STOP_WORDS) {
            stop()
            return
        }
        pipelineStage = PipelineStage.AWAITING_RESPONSE
        setStageTimeout(seconds = 10, expected = "synthesize/pipeline-ended")
    }

    internal fun handleSynthesize() {
        if (pipelineStage != PipelineStage.STREAMING_TTS) {
            pipelineStage = PipelineStage.AWAITING_TTS
        }
        setStageTimeout(seconds = 10, expected = "audio-start")
    }

    internal fun handleAudioStart(msg: WyomingPacket) {
        cancelStageTimeout()
        pipelineStage = PipelineStage.STREAMING_TTS
    }

    internal fun handleAudioChunk(event: WyomingPacket) {
        if (pipelineStage == PipelineStage.STREAMING_TTS && mediaManager.voicePlayer.isPlaying()) {
            mediaManager.voicePlayer.writeData(event.payload)
        }
    }

    internal fun handleAudioStop() {
        cancelStageTimeout()
        scope.launch {
            try {
                if (mediaManager.voicePlayer.isPlaying()) {
                    // We send 'played' but we DON'T stop immediately, to allow draining.
                    // The next synthesizer or a reset will stop it properly.
                    mediaManager.voicePlayer.flush()
                    withTimeout(10000) {
                        while (mediaManager.voicePlayer.isPlaying()) {
                            delay(100)
                            yield()
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.d("Audio stop timed out")
            } finally {
                withContext(NonCancellable) {
                    mediaManager.voicePlayer.stop()
                    sendMessage(buildPlayedMessage())
                    pipelineRunning.complete(PipelineEndReason.END_OF_PIPELINE)
                }
            }
        }
    }

    internal fun handlePipelineEnded() {
        cancelStageTimeout()
        if (pipelineStage == PipelineStage.AWAITING_TTS || pipelineStage == PipelineStage.STREAMING_TTS) {
            Timber.d("Pipeline ended but TTS is in stage $pipelineStage. Waiting for TTS audio to complete")
        } else {
            pipelineRunning.complete(PipelineEndReason.END_OF_PIPELINE)
        }
    }


    internal fun handlePipelineError(event: WyomingPacket) {
        cancelStageTimeout()
        val code = event.getProp("code")
        val text = event.getProp("text")

        val isDuplicateWakeUp = code == "duplicate_wake_up_detected"

        if (isDuplicateWakeUp) {
            Timber.d("Speech-to-text cancelled to avoid duplicate wake-up. Handled gracefully.")
            pipelineRunning.complete(PipelineEndReason.DUPLICATE_WAKEUP)
            return
        }

        val toastMessage = text.ifEmpty { "Error: $code" }
        config.eventBroadcaster.notifyEvent(Event("recognitionError", toastMessage, code))

        pipelineRunning.complete(PipelineEndReason.ERRORED)
    }

    private fun setStageTimeout(seconds: Int, expected: String) {
        cancelStageTimeout()
        stageTimeoutJob = scope.launch {
            delay(seconds * 1000L)
            if (!pipelineRunning.isCompleted) {
                Timber.w("Pipeline timeout [$pipelineId] at stage $pipelineStage waiting for $expected")
                if (pipelineStage == PipelineStage.LISTENING ||
                    pipelineStage == PipelineStage.VOICE_STARTED ||
                    pipelineStage == PipelineStage.VOICE_STOPPED
                ) {
                    sendMessage(buildAudioStopMessage())
                }
                pipelineRunning.complete(PipelineEndReason.TIMED_OUT)
            }
        }
    }

    private fun cancelStageTimeout() {
        stageTimeoutJob?.cancel()
        stageTimeoutJob = null
    }


    /**
     * Notifies of wake word detection.
     */
    internal fun buildDetectionMessage(): WyomingPacket {
        val detectionPacket = WyomingPacket(
            "detection",
            buildJsonObject {
                put("name", config.wakeWord)
                put("timestamp", isoNow())
                put("speaker", "")
            },
        )
        return detectionPacket
    }

    /**
     * Initiates a pipeline run.
     */
    internal fun buildRunPipelineMessage(): WyomingPacket {
        val packet = WyomingPacket(
            "run-pipeline",
            buildJsonObject {
                put("name", "VACA ${config.uuid}")
                put("start_stage", "asr")
                put("end_stage", "tts")
                put("restart_on_end", false)
                putJsonObject("snd_format") {
                    put("rate", config.sampleRate)
                    put("width", config.audioWidth)
                    put("channels", config.audioChannels)
                }
            }
        )
        return packet
    }

    internal fun buildAudioPacketMessage(audio: ByteArray): WyomingPacket {
        val packet = WyomingPacket(
            "audio-chunk",
            buildJsonObject {
                put("rate", config.sampleRate)
                put("width", config.audioWidth)
                put("channels", config.audioChannels)
            },
            audio
        )
        return packet
    }

    internal fun buildAudioStopMessage(): WyomingPacket {
        val packet = WyomingPacket(
            "audio-stop",
            buildJsonObject { put("timestamp", isoNow()) },
            ByteArray(0),
        )
        return packet
    }

    internal fun buildPlayedMessage(): WyomingPacket {
        val packet = WyomingPacket(
            "played",
            buildJsonObject {},
            ByteArray(0),
        )
        return packet
    }
}
