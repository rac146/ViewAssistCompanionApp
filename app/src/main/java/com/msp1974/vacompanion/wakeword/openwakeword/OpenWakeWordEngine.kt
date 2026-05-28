package com.msp1974.vacompanion.wakeword.openwakeword

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.annotation.RequiresPermission
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.google.protobuf.ByteString
import com.msp1974.vacompanion.audio.AudioDSP
import com.msp1974.vacompanion.audio.MicrophoneInput
import com.msp1974.vacompanion.audio.VACAAudioFormat
import com.msp1974.vacompanion.device.DeviceCapabilitiesManager
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import com.msp1974.vacompanion.wakeword.openwakeword.audio.AudioProcessor
import com.msp1974.vacompanion.wakeword.openwakeword.ml.ModelRunner
import com.msp1974.vacompanion.wakeword.openwakeword.ml.OnnxModelRunner
import com.msp1974.vacompanion.wakeword.openwakeword.ml.TfliteModelRunner
import com.msp1974.vacompanion.wakeword.openwakeword.model.WakeWordModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.LinkedList
import kotlin.math.sqrt


/**
 * Main entry point for wake word detection using ONNX Runtime.
 *
 * This class manages multiple wake word models and emits detection events through a Kotlin Flow.
 * It provides real-time audio processing with configurable detection modes and cooldown periods.
 */
class OpenWakeWordEngine(
    private val context: Context,
    val config: APPConfig,
    private val models: List<WakeWordModel>,
    private val detectionCooldownMs: Long = 2000L,
    muted: Boolean = false
): WakeWordEngineProvider() {

    private val assetManager: AssetManager = context.assets
    private val modelProcessors = mutableMapOf<WakeWordModel, ModelProcessor>()
    private val scoreWindows = mutableMapOf<String, ArrayDeque<Float>>()
    private val detectionStates = mutableMapOf<String, OwwDetectionState>()

    var isEnabled = true

    private var _audioProcessor: AudioProcessor = AudioProcessor(assetManager)
    private val audioRingBuffer = LinkedList<FloatArray>()
    private val audioRingBufferLock = Any()
    private val audioRingBufferMaxFrames = 20
    private val speakerVerificationWindowMs = 750
    private val speakerVerificationMinWindowMs = 250
    private val speakerVerificationNumThreads = 2
    private val speakerVerificationRejectCooldownMs = 2500L
    private val speakerVerificationAcceptCooldownMs = 5000L
    private val speakerVerificationOwwCooldownMs = 5000L
    private var speakerVerifier: SherpaSpeakerVerifier? = null
    private var speakerVerifierSignature: String? = null
    @Volatile private var suppressOwwUntilMs: Long = 0L
    @Volatile private var speakerEnrollmentRequested = false
    private var enrollmentState: SpeakerEnrollmentState? = null

    private data class SpeakerEnrollmentState(
        val targetSamples: Int,
        val utterances: MutableList<FloatArray> = mutableListOf(),
        val currentFrames: MutableList<FloatArray> = mutableListOf(),
        var startedSpeech: Boolean = false,
        var armDelayFrames: Int = 0,
        var speechFrames: Int = 0,
        var silenceFrames: Int = 0,
        var totalFrames: Int = 0,
    )

    private data class OwwDetectionState(
        var consecutiveHits: Int = 0,
        var lastTriggeredAtMs: Long = 0L
    )

    /**
     * Flow of wake word detection events.
     *
     * This Flow emits [WakeWordDetection] objects whenever a wake word is detected.
     * The Flow is hot and shared, meaning multiple collectors will receive the same events.
     *
     * ## Example: Basic Collection
     * ```kotlin
     * engine.detections.collect { detection ->
     *     showToast("${detection.model.name} detected!")
     * }
     * ```
     *
     * ## Example: Filtering High-Confidence Detections
     * ```kotlin
     * engine.detections
     *     .filter { it.score > 0.8f }
     *     .collect { detection ->
     *         // Only process high-confidence detections
     *     }
     * ```
     *
     * ## Example: Debouncing Rapid Detections
     * ```kotlin
     * engine.detections
     *     .debounce(500) // Additional debounce on top of cooldown
     *     .collect { detection ->
     *         // Process debounced detections
     *     }
     * ```
     */

    /**
     * Flow of real-time wake word scores.
     *
     * This Flow emits [WakeWordScore] objects continuously for all models,
     * regardless of whether they exceed the detection threshold.
     * Useful for real-time monitoring and visualization.
     */

    init {
        require(models.isNotEmpty()) { "At least one wake word model must be provided" }
        initializeModels()
    }

    private fun initializeModels() {
        models.forEach { model ->
            val processor = ModelProcessor(assetManager, config.wakeWordEngine, model)
            modelProcessors[model] = processor
        }
    }

    fun addModel(model: WakeWordModel) {
        /**
        Add model to detections
         */
        Timber.w("Adding model ${model.name} to engine")
        modelProcessors.forEach {(wakeWordModel, processor) ->
            if (wakeWordModel.name == model.name) {
                throw IllegalArgumentException("Model with name ${model.name} already exists")
            }
        }
        modelProcessors[model] = ModelProcessor(assetManager, config.wakeWordEngine, model)
        scoreWindows.remove(model.name)
        detectionStates.remove(model.name)
    }

    fun removeModel(modelName: String) {
        /**
        Remove model from detections
         */
        Timber.w("Removing model $modelName from engine")
        modelProcessors.forEach {(wakeWordModel, processor) ->
            if (wakeWordModel.name == modelName) {
                processor.close()
                modelProcessors.remove(wakeWordModel)
                scoreWindows.remove(modelName)
                detectionStates.remove(modelName)
                return
            }
        }
        throw IllegalArgumentException("Model with name $modelName not found")
    }

    private val _muted = MutableStateFlow(muted)
    val muted = _muted.asStateFlow()
    override fun setMuted(value: Boolean) {
        _muted.value = value
    }

    override fun isMuted(): Boolean {
        return _muted.value
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() = muted.flatMapLatest {
        if (it) emptyFlow()
        else flow {
            val isEmbedded = DeviceCapabilitiesManager(context, config).isAndroidThings()
            val audioSource = if(isEmbedded) VACAAudioFormat.FALLBACK_AUDIO_SOURCE else VACAAudioFormat.DEFAULT_AUDIO_SOURCE
            val microphoneInput = MicrophoneInput(config, audioSource, frameSize = 1280)
            try {
                microphoneInput.start()
                emit(AudioResult.EngineStatus("Started"))
                while (true) {
                    val audio = microphoneInput.readFloat()
                    val frameTimestamp = System.currentTimeMillis()

                    if (audio.isNotEmpty()) {
                        if (speakerEnrollmentRequested) {
                            beginSpeakerEnrollment()
                        }

                        if (enrollmentState != null) {
                            processEnrollmentAudio(audio)
                            continue
                        }

                        addToAudioRingBuffer(audio)

                        if (config.diagnosticsEnabled) {
                            emit(AudioResult.AudioLevel(AudioDSP().audioLevel(audio)))
                        }

                        if (isStreaming) {
                            val a = AudioDSP().floatArrayToByteBuffer(audio)
                            emit(
                                AudioResult.Audio(
                                    ByteString.copyFrom(a),
                                    timestamp = frameTimestamp
                                )
                            )
                        }

                        val detections = processAudio(audio, frameTimestamp)
                        for (detection in detections) {
                            if (detection.detected) {
                                val nowMs = System.currentTimeMillis()
                                if (nowMs < suppressOwwUntilMs) {
                                    Timber.i(
                                        "OWW trigger suppressed wake='%s' score=%.4f ts=%d until=%d",
                                        detection.wakeWord,
                                        detection.score,
                                        detection.timestamp,
                                        suppressOwwUntilMs
                                    )
                                    continue
                                }

                                Timber.i(
                                    "OWW trigger detected wake='%s' score=%.4f ts=%d",
                                    detection.wakeWord,
                                    detection.score,
                                    detection.timestamp
                                )
                                // Capture one extra frame after detection so the ring buffer
                                // includes a small post-trigger context window.
                                val paddingAudio = microphoneInput.readFloat()
                                if (paddingAudio.isNotEmpty()) {
                                    addPaddingToAudioRingBuffer(paddingAudio)
                                }
                                val accepted = verifySpeakerIfEnabled(detection)
                                if (accepted) {
                                    suppressOwwUntilMs = System.currentTimeMillis() + speakerVerificationAcceptCooldownMs
                                    emit(AudioResult.WakeDetected(detection))
                                } else {
                                    Timber.i(
                                        "OWW trigger rejected by speaker verification wake='%s' score=%.4f ts=%d",
                                        detection.wakeWord,
                                        detection.score,
                                        detection.timestamp
                                    )
                                }
                            }
                        }
                    }
                    yield()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                Timber.e("Runtime exception thrown by wake word engine: $e")
            } finally {
                microphoneInput.close()
                emit(AudioResult.EngineStatus("Stopped"))
            }
        }
    }

    override fun startSpeakerEnrollment() {
        speakerEnrollmentRequested = true
    }

    @SuppressLint("DefaultLocale")
    fun processAudio(audioBuffer: FloatArray, timestamp: Long = System.currentTimeMillis()): List<WakeWordDetection> {
        val detections = mutableListOf<WakeWordDetection>()

        if (isEnabled) {
            val audioFeatures = _audioProcessor.getAudioFeatures(audioBuffer)
            modelProcessors.map { (model, processor) ->
                try {
                    val score = processor.process(audioFeatures)
                    val smoothedScore = updateSmoothedScore(
                        modelName = model.name,
                        score = score,
                        windowSize = config.experimentalMwwSmoothingWindow
                    )
                    val shouldTrigger = evaluateTrigger(
                        modelName = model.name,
                        smoothedScore = smoothedScore,
                        threshold = model.threshold,
                        requiredHits = config.experimentalMwwConsecutiveHits,
                        cooldownMs = resolveOwwCooldownMs(),
                        nowMs = timestamp
                    )
                    if (shouldTrigger) {
                        detections.add(
                            WakeWordDetection(
                                model.name,
                                model.name,
                                detected = true,
                                score = smoothedScore,
                                timestamp = timestamp
                            )
                        )
                    }
                } catch (e: RuntimeException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e("Error processing model ${model.name} ->$e")
                    e.printStackTrace()
                }
            }
        }
        return detections
    }

    private fun updateSmoothedScore(modelName: String, score: Float, windowSize: Int): Float {
        val size = windowSize.coerceAtLeast(1)
        val window = scoreWindows.getOrPut(modelName) { ArrayDeque(size) }
        if (window.size == size) {
            window.removeFirst()
        }
        window.addLast(score)
        return window.average().toFloat()
    }

    private fun evaluateTrigger(
        modelName: String,
        smoothedScore: Float,
        threshold: Float,
        requiredHits: Int,
        cooldownMs: Long,
        nowMs: Long
    ): Boolean {
        return shouldTrigger(
            modelName = modelName,
            smoothedScore = smoothedScore,
            threshold = threshold,
            requiredHits = requiredHits.coerceAtLeast(1),
            cooldownMs = cooldownMs.coerceAtLeast(0L),
            nowMs = nowMs
        )
    }

    private fun resolveOwwCooldownMs(): Long {
        val baseCooldown = maxOf(detectionCooldownMs, config.experimentalMwwCooldownMs.toLong())
        return if (config.speakerVerificationEnabled) {
            maxOf(baseCooldown, speakerVerificationOwwCooldownMs)
        } else {
            baseCooldown
        }
    }

    private fun shouldTrigger(
        modelName: String,
        smoothedScore: Float,
        threshold: Float,
        requiredHits: Int,
        cooldownMs: Long,
        nowMs: Long
    ): Boolean {
        val state = detectionStates.getOrPut(modelName) { OwwDetectionState() }
        state.consecutiveHits = if (smoothedScore >= threshold) state.consecutiveHits + 1 else 0
        val onCooldown = nowMs - state.lastTriggeredAtMs < cooldownMs
        if (state.consecutiveHits >= requiredHits && !onCooldown) {
            state.lastTriggeredAtMs = nowMs
            state.consecutiveHits = 0
            return true
        }
        return false
    }

    fun enable() {
        isEnabled = true
    }

    fun disable() {
        isEnabled = false
    }

    fun reset() {
        _audioProcessor.reset()
        scoreWindows.clear()
        detectionStates.clear()
    }

    fun getAudioRingBufferSnapshot(): List<FloatArray> {
        synchronized(audioRingBufferLock) {
            return audioRingBuffer.map { it.copyOf() }
        }
    }

    fun clearAudioRingBuffer() {
        synchronized(audioRingBufferLock) {
            audioRingBuffer.clear()
        }
    }

    private fun addToAudioRingBuffer(frame: FloatArray) {
        synchronized(audioRingBufferLock) {
            audioRingBuffer.add(frame.copyOf())
            while (audioRingBuffer.size > audioRingBufferMaxFrames) {
                audioRingBuffer.removeFirst()
            }
        }
    }

    private fun addPaddingToAudioRingBuffer(frame: FloatArray) {
        synchronized(audioRingBufferLock) {
            audioRingBuffer.addLast(frame.copyOf())
            while (audioRingBuffer.size > audioRingBufferMaxFrames + 1) {
                audioRingBuffer.removeFirst()
            }
        }
    }

    private fun beginSpeakerEnrollment() {
        speakerEnrollmentRequested = false
        if (enrollmentState != null) {
            toast("Speaker enrollment already in progress")
            return
        }

        val modelPath = config.speakerVerificationModelPath.trim()
        if (modelPath.isEmpty()) {
            toast("Speaker enrollment: model path is missing")
            return
        }

        enrollmentState = SpeakerEnrollmentState(targetSamples = 5)
        armEnrollmentForNextSample()
        toast("Speaker enrollment started. Wait for beep, then say '${config.wakeWord}' clearly 5 times.")
    }

    private fun processEnrollmentAudio(frame: FloatArray) {
        val state = enrollmentState ?: return
        val rms = frameRms(frame)

        val speechStartThreshold = 0.015f
        val speechEndThreshold = 0.010f
        val minSpeechFrames = 4
        val endSilenceFrames = 4
        val maxUtteranceFrames = 35

        if (!state.startedSpeech) {
            if (state.armDelayFrames > 0) {
                state.armDelayFrames -= 1
                return
            }
            if (rms >= speechStartThreshold) {
                state.startedSpeech = true
                state.speechFrames = 1
                state.totalFrames = 1
                state.silenceFrames = 0
                state.currentFrames.add(frame.copyOf())
            }
            return
        }

        state.currentFrames.add(frame.copyOf())
        state.totalFrames += 1

        if (rms >= speechEndThreshold) {
            state.speechFrames += 1
            state.silenceFrames = 0
        } else {
            state.silenceFrames += 1
        }

        val reachedSpeechStop = state.speechFrames >= minSpeechFrames && state.silenceFrames >= endSilenceFrames
        val reachedMaxFrames = state.totalFrames >= maxUtteranceFrames
        if (!reachedSpeechStop && !reachedMaxFrames) {
            return
        }

        val utterance = flattenFrames(state.currentFrames)
        if (utterance.isNotEmpty()) {
            state.utterances.add(utterance)
            toast("Captured sample ${state.utterances.size}/${state.targetSamples}.")
        }

        if (state.utterances.size >= state.targetSamples) {
            finishSpeakerEnrollment(state)
            enrollmentState = null
            return
        }

        state.currentFrames.clear()
        state.startedSpeech = false
        armEnrollmentForNextSample()
        state.speechFrames = 0
        state.silenceFrames = 0
        state.totalFrames = 0
        toast("Wait for beep. Say '${config.wakeWord}' again.")
    }

    private fun armEnrollmentForNextSample() {
        val state = enrollmentState ?: return
        // Ignore immediate post-beep frames so we don't treat cue/beep as speech.
        // With ~80ms frames this gives ~400ms arm time.
        state.armDelayFrames = 5
        playEnrollmentCueBeep()
    }

    private fun playEnrollmentCueBeep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
            try {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 140)
            } finally {
                tone.release()
            }
        }
    }

    private fun finishSpeakerEnrollment(state: SpeakerEnrollmentState) {
        val configuredModelPath = config.speakerVerificationModelPath.trim()
        if (configuredModelPath.isEmpty()) {
            toast("Speaker enrollment failed: model path is missing")
            return
        }

        val localModelPath = try {
            resolveSpeakerModelFilePath(configuredModelPath)
        } catch (t: Throwable) {
            Timber.w(t, "Speaker enrollment model resolve failed")
            toast("Speaker enrollment failed: model path error")
            return
        }

        val extractor = try {
            val extractorConfig = SpeakerEmbeddingExtractorConfig(
                localModelPath,
                speakerVerificationNumThreads,
                false,
                "cpu"
            )
            SpeakerEmbeddingExtractor(null, extractorConfig)
        } catch (t: Throwable) {
            Timber.w(t, "Speaker enrollment extractor init failed")
            toast("Speaker enrollment failed: extractor init error")
            return
        }

        try {
            val embeddings = mutableListOf<FloatArray>()
            state.utterances.forEach { utterance ->
                val stream = extractor.createStream()
                stream.acceptWaveform(utterance, config.sampleRate)
                stream.inputFinished()
                if (extractor.isReady(stream)) {
                    embeddings.add(extractor.compute(stream))
                }
                stream.release()
            }

            if (embeddings.isEmpty()) {
                toast("Speaker enrollment failed: no valid samples")
                return
            }

            val averaged = averageNormalizedEmbeddings(embeddings)
            if (averaged.isEmpty()) {
                toast("Speaker enrollment failed: invalid embedding")
                return
            }

            val outputPath = resolveEnrollmentOutputPath()
            saveEmbedding(outputPath, averaged)
            config.speakerVerificationEmbeddingPath = outputPath
            config.speakerVerificationEnabled = true
            speakerVerifierSignature = null
            toast("Speaker enrollment complete (${embeddings.size} samples)")
        } catch (t: Throwable) {
            Timber.w(t, "Speaker enrollment processing failed")
            toast("Speaker enrollment failed: processing error")
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun resolveEnrollmentOutputPath(): String {
        val configured = config.speakerVerificationEmbeddingPath.trim()
        if (configured.isNotEmpty()) return configured

        val dir = File(context.filesDir, "speaker")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "enrolled_embedding.txt").absolutePath
    }

    private fun saveEmbedding(path: String, embedding: FloatArray) {
        val output = File(path)
        output.parentFile?.mkdirs()
        output.writeText(
            embedding.joinToString(
                prefix = "[",
                postfix = "]",
                separator = ","
            ) { value -> value.toString() },
            Charsets.UTF_8
        )
    }

    private fun flattenFrames(frames: List<FloatArray>): FloatArray {
        if (frames.isEmpty()) return FloatArray(0)
        val total = frames.sumOf { it.size }
        val merged = FloatArray(total)
        var offset = 0
        frames.forEach { frame ->
            System.arraycopy(frame, 0, merged, offset, frame.size)
            offset += frame.size
        }
        return merged
    }

    private fun frameRms(frame: FloatArray): Float {
        if (frame.isEmpty()) return 0f
        var sum = 0.0
        frame.forEach { sample ->
            val s = sample.toDouble()
            sum += s * s
        }
        return sqrt(sum / frame.size).toFloat()
    }

    private fun averageNormalizedEmbeddings(embeddings: List<FloatArray>): FloatArray {
        if (embeddings.isEmpty()) return FloatArray(0)
        val dim = embeddings.first().size
        if (dim == 0 || embeddings.any { it.size != dim }) return FloatArray(0)

        val accumulator = FloatArray(dim)
        embeddings.forEach { embedding ->
            val normalized = l2Normalize(embedding)
            for (i in 0 until dim) {
                accumulator[i] += normalized[i]
            }
        }

        for (i in 0 until dim) {
            accumulator[i] /= embeddings.size.toFloat()
        }
        return l2Normalize(accumulator)
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var norm = 0.0
        vector.forEach { v ->
            val d = v.toDouble()
            norm += d * d
        }
        val denom = sqrt(norm)
        if (denom <= 1e-12) return vector.copyOf()

        val out = FloatArray(vector.size)
        for (i in vector.indices) {
            out[i] = (vector[i] / denom).toFloat()
        }
        return out
    }

    private fun toast(message: String) {
        config.eventBroadcaster.notifyEvent(Event("showToastMessage", "", message))
    }

    private fun verifySpeakerIfEnabled(detection: WakeWordDetection): Boolean {
        val tStartNs = System.nanoTime()
        maybeEnableSpeakerVerificationFromExistingEnrollment()
        if (!config.speakerVerificationEnabled) {
            return true
        }

        val verifier = getOrCreateSpeakerVerifier()
        if (verifier == null || !verifier.isReady) {
            return if (config.speakerVerificationFailOpen) {
                Timber.w("Speaker verifier unavailable, fail-open enabled")
                true
            } else {
                Timber.w("Speaker verifier unavailable, wake rejected (fail-open disabled)")
                false
            }
        }

        val tRingStartNs = System.nanoTime()
        val verificationAudio = flattenAudioRingBufferTail(speakerVerificationWindowMs)
        val ringCopyMs = ((System.nanoTime() - tRingStartNs) / 1_000_000.0)
        val verificationDurationMs = ((verificationAudio.size.toFloat() / config.sampleRate.toFloat()) * 1000f).toInt()
        if (verificationDurationMs < speakerVerificationMinWindowMs) {
            Timber.w(
                "Speaker verification skipped for wake '%s': short audio (%dms < %dms)",
                detection.wakeWord,
                verificationDurationMs,
                speakerVerificationMinWindowMs
            )
            return config.speakerVerificationFailOpen
        }

        val tVerifyStartNs = System.nanoTime()
        val result = verifier.verify(verificationAudio)
        val verifyMs = ((System.nanoTime() - tVerifyStartNs) / 1_000_000.0)
        val totalMs = ((System.nanoTime() - tStartNs) / 1_000_000.0)
        val accepted = result.accepted
        val score = result.score
        val threshold = config.speakerVerificationThreshold
        Timber.i(
            "Speaker verification result wake='%s' score=%.4f threshold=%.2f accepted=%s reason=%s audioMs=%d",
            detection.wakeWord,
            score,
            threshold,
            accepted,
            result.reason,
            verificationDurationMs
        )
        Timber.i(
            "Speaker verification timing wake='%s' audioMs=%d samples=%d ring_copy_ms=%.1f verify_ms=%.1f total_ms=%.1f",
            detection.wakeWord,
            verificationDurationMs,
            verificationAudio.size,
            ringCopyMs,
            verifyMs,
            totalMs
        )

        if (accepted) {
            Timber.i(
                "Speaker verified for wake '%s' score=%.4f threshold=%.2f",
                detection.wakeWord,
                score,
                threshold
            )
            return true
        }

        Timber.w(
            "Speaker verification rejected wake '%s' score=%.4f threshold=%.2f reason=%s",
            detection.wakeWord,
            score,
            threshold,
            result.reason
        )
        suppressOwwUntilMs = System.currentTimeMillis() + speakerVerificationRejectCooldownMs
        return config.speakerVerificationFailOpen && result.reason != "below_threshold"
    }

    private fun maybeEnableSpeakerVerificationFromExistingEnrollment() {
        if (config.speakerVerificationEnabled) return
        if (!hasSpeakerModelConfigured()) return

        val existingEmbeddingPath = resolveExistingEnrollmentPath() ?: return
        if (config.speakerVerificationEmbeddingPath.trim().isEmpty()) {
            config.speakerVerificationEmbeddingPath = existingEmbeddingPath
        }
        config.speakerVerificationEnabled = true
        Timber.i("Auto-enabled speaker verification from existing enrollment: %s", existingEmbeddingPath)
    }

    private fun resolveExistingEnrollmentPath(): String? {
        val configured = config.speakerVerificationEmbeddingPath.trim()
        if (configured.isNotEmpty() && File(configured).exists()) {
            return configured
        }

        val defaultPath = File(context.filesDir, "speaker/enrolled_embedding.txt")
        return if (defaultPath.exists()) defaultPath.absolutePath else null
    }

    private fun hasSpeakerModelConfigured(): Boolean {
        val modelPath = config.speakerVerificationModelPath.trim()
        if (modelPath.isEmpty()) return false
        if (File(modelPath).exists()) return true
        return runCatching {
            context.assets.open(modelPath).use { }
            true
        }.getOrElse { false }
    }

    private fun resolveSpeakerModelFilePath(modelPath: String): String {
        val direct = File(modelPath)
        if (direct.isFile) {
            return direct.absolutePath
        }

        val targetDir = File(context.filesDir, "speaker-models")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val ext = modelPath.substringAfterLast('.', "onnx")
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(modelPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val targetFile = File(targetDir, "speaker-model-$digest.$ext")
        if (!targetFile.exists() || targetFile.length() == 0L) {
            context.assets.open(modelPath).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return targetFile.absolutePath
    }

    private fun getOrCreateSpeakerVerifier(): SherpaSpeakerVerifier? {
        val signature = buildSpeakerVerifierSignature()
        if (speakerVerifier != null && speakerVerifierSignature == signature) {
            return speakerVerifier
        }

        runCatching { speakerVerifier?.close() }
        speakerVerifier = null
        speakerVerifierSignature = signature

        return try {
            val tCreateStartNs = System.nanoTime()
            SherpaSpeakerVerifier(context, config).also {
                speakerVerifier = it
                val createMs = ((System.nanoTime() - tCreateStartNs) / 1_000_000.0)
                Timber.i(
                    "Speaker verifier (re)created in %.1fms ready=%s model='%s'",
                    createMs,
                    it.isReady,
                    config.speakerVerificationModelPath
                )
            }
        } catch (t: Throwable) {
            Timber.w(t, "Failed to create speaker verifier")
            null
        }
    }

    private fun buildSpeakerVerifierSignature(): String {
        return listOf(
            config.speakerVerificationEnabled.toString(),
            config.speakerVerificationThreshold.toString(),
            config.speakerVerificationModelPath,
            config.speakerVerificationEmbeddingPath,
            config.speakerVerificationFailOpen.toString()
        ).joinToString("|")
    }

    private fun flattenAudioRingBufferTail(windowMs: Int): FloatArray {
        val snapshot = getAudioRingBufferSnapshot()
        if (snapshot.isEmpty()) return FloatArray(0)

        val maxSamples = ((config.sampleRate.toLong() * windowMs.toLong()) / 1000L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        val selected = ArrayList<FloatArray>()
        var collected = 0
        var idx = snapshot.lastIndex
        while (idx >= 0 && collected < maxSamples) {
            val frame = snapshot[idx]
            selected.add(frame)
            collected += frame.size
            idx--
        }

        if (selected.isEmpty()) return FloatArray(0)
        selected.reverse()

        val totalSelected = selected.sumOf { it.size }
        val merged = FloatArray(totalSelected)
        var offset = 0
        selected.forEach { frame ->
            System.arraycopy(frame, 0, merged, offset, frame.size)
            offset += frame.size
        }

        if (merged.size <= maxSamples) return merged
        return merged.copyOfRange(merged.size - maxSamples, merged.size)
    }

    /**
     * Stops wake word detection.
     *
     * This method stops audio recording and cancels all ongoing detection processing.
     * The engine can be restarted by calling [start] again.
     *
     * ## Example
     * ```kotlin
     * override fun onPause() {
     *     super.onPause()
     *     engine.stop() // Stop detection when app goes to background
     * }
     * ```
     *
     * @see start
     */
    fun stop() {

    }

    /**
     * Releases all resources used by the engine.
     *
     * This method should be called when the engine is no longer needed to free up memory
     * and system resources. After calling this method, the engine cannot be reused.
     *
     * ## Important
     * Always call this method in your Activity/Fragment's onDestroy() to prevent memory leaks.
     *
     * ## Example
     * ```kotlin
     * override fun onDestroy() {
     *     super.onDestroy()
     *     wakeWordEngine?.release()
     * }
     * ```
     *
     * This method will:
     * - Stop any ongoing detection
     * - Release ONNX Runtime sessions
     * - Free audio processing resources
     * - Clear internal caches
     */
    override fun release() {
        stop()
        runCatching { speakerVerifier?.close() }
        speakerVerifier = null
        speakerVerifierSignature = null
        enrollmentState = null
        speakerEnrollmentRequested = false
        modelProcessors.values.forEach { it.close() }
        modelProcessors.clear()
    }

    /**
     * Internal class to process audio for a specific model.
     */
    private class ModelProcessor(
        assetManager: AssetManager,
        engine: String,
        model: WakeWordModel
    ) : AutoCloseable {

        private val modelRunner = getModelRunner(engine, assetManager, model)

        fun getModelRunner(engine: String, assetManager: AssetManager, model: WakeWordModel): ModelRunner {
            return if (engine == "openwakeword") OnnxModelRunner(assetManager, model)
            else TfliteModelRunner(assetManager, model)
        }

        fun process(audioFeatures: Array<Array<FloatArray>>): Float {
            val score = modelRunner.predictWakeWord(audioFeatures)
            return score
        }

        override fun close() {
            modelRunner.close()
        }
    }
}
