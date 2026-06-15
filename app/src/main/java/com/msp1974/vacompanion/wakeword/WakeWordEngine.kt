package com.msp1974.vacompanion.wakeword

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Environment
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.msp1974.vacompanion.audio.AudioDSP
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.Helpers.Companion.round
import com.msp1974.vacompanion.wakeword.microwakeword.MicroWakeWordEngine
import com.msp1974.vacompanion.wakeword.microwakeword.providers.MicroWakeWordAssetProvider
import com.msp1974.vacompanion.wakeword.models.WakeWordWithId
import com.msp1974.vacompanion.wakeword.openwakeword.OpenWakeWordEngine
import com.msp1974.vacompanion.wakeword.openwakeword.SherpaSpeakerVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.LinkedList
import kotlin.math.sqrt

open class WakeWordEngine(val context: Context, val config: APPConfig, val engine: WakeWordEngineModel, val isAndroidThings: Boolean) {

    private var activeWakeWords: List<String> = listOf()
    private var activeStopWords: List<String> = listOf()
    private var engineInstance: WakeWordEngineProvider? = null
    private val audioDsp = AudioDSP()

    // Shared speaker verification runtime path (applies to OWW + MWW)
    private val verificationRingBuffer = LinkedList<VerificationTraceFrame>()
    private val verificationRingBufferLock = Any()
    private val speakerVerificationPreRollMs = 1900L
    private val speakerVerificationPostRollMs = 250L
    private val speakerVerificationMaxWindowMs = 3200L
    private val preVerificationBeepMs = 70
    private val duplicateWakeSuppressionMs = 1200L
    private val wakeTriggerHistory = LinkedList<Pair<String, Long>>()
    private val speakerVerificationNumThreads = 2
    @Volatile private var speakerEnrollmentRequested = false
    private var enrollmentState: SpeakerEnrollmentState? = null
    private val wakeClipDumpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class VerificationTraceFrame(
        val timestamp: Long,
        val audio: FloatArray,
        val scores: Map<String, Float>
    )

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

    private suspend fun get(): WakeWordEngineProvider? {
        Timber.i("Starting ${config.wakeWordEngine} wake word engine")


        if (config.availableWakeWords == null) {
            Timber.e("No available wake words")
            return null
        }

        val availableWakeWords = config.availableWakeWords?.get(engine.toString()) ?: emptyList()

        when(engine) {
            WakeWordEngineModel.MICROWAKEWORD -> {
                // TODO: Replace this with values from config availableWakeWords
                // ATM this does not load stop words
                val availableStopWords = MicroWakeWordAssetProvider(
                    context.assets,
                    "microwakeword/stopWords"
                ).get()
                return MicroWakeWordEngine(context, config, activeWakeWords, activeStopWords, availableWakeWords, availableStopWords, isAndroidThings = isAndroidThings, muted = config.isMuted)
            }
            WakeWordEngineModel.OPENWAKEWORD -> {
                availableWakeWords.forEach { entry ->
                    if (config.wakeWord == entry.id) {
                        return OpenWakeWordEngine(
                            context = context,
                            config = config,
                            engine = WakeWordEngineModel.OPENWAKEWORD,
                            activeWakeWords = activeWakeWords,
                            availableWakeWords = availableWakeWords,
                            detectionCooldownMs = 1500L,
                            isAndroidThings = isAndroidThings,
                            muted = config.isMuted,

                        )
                    }
                }
            }
            WakeWordEngineModel.OPENWAKEWORD_RT -> {
                availableWakeWords.forEach { entry ->
                    if (config.wakeWord == entry.id) {
                        return OpenWakeWordEngine(
                            context = context,
                            config = config,
                            engine = WakeWordEngineModel.OPENWAKEWORD_RT,
                            activeWakeWords = activeWakeWords,
                            availableWakeWords = availableWakeWords,
                            detectionCooldownMs = 1500L,
                            isAndroidThings = isAndroidThings,
                            muted = config.isMuted
                        )
                    }
                }
            }
        }
        return null
    }

    fun getAvailableWakeWords(): List<WakeWordWithId> {
        return config.availableWakeWords?.get(engine.toString()) ?: emptyList()
    }


    fun setActiveWakeWords(value: List<String>) {
        activeWakeWords = value
    }

    fun setActiveStopWords(value: List<String>) {
        activeStopWords = value
    }

    fun setStreaming(stream: Boolean) {
        if (engineInstance != null) {
            engineInstance!!.isStreaming = stream
        }
    }

    fun isStreaming(): Boolean {
        if (engineInstance != null) {
            return engineInstance!!.isStreaming
        }
        return false
    }

    fun setMuted(value: Boolean) {
        if (engineInstance != null) {
            engineInstance!!.setMuted(value)
        }
    }

    fun isMuted(): Boolean {
        if (engineInstance != null) {
            return engineInstance!!.isMuted()
        }
        return false
    }

    fun startSpeakerEnrollment() {
        speakerEnrollmentRequested = true
    }

    fun start() = flow {
        maybeEnableSpeakerVerificationFromExistingEnrollment()
        engineInstance = get()
        synchronized(verificationRingBufferLock) {
            verificationRingBuffer.clear()
        }
        enrollmentState = null
        wakeTriggerHistory.clear()
        var sharedVerifier: SherpaSpeakerVerifier? = createSharedVerifierIfEnabled()

        if (engineInstance != null) {
            try {
                engineInstance!!.start()!!.collect {
                    when (it) {
                        is WakeWordEngineProvider.AudioResult.WakeDetected -> {
                            val rawDetected = WakeWordEngineProvider.WakeWordDetection(
                                it.detection.wakeWordId,
                                it.detection.wakeWord,
                                it.detection.detected,
                                it.detection.score,
                                timestamp = it.detection.timestamp
                            )

                            if (enrollmentState != null) {
                                Timber.i("Suppressing wake detection during speaker enrollment")
                                return@collect
                            }
                            if (isDuplicateWakeTrigger(rawDetected)) {
                                Timber.i(
                                    "Suppressing duplicate wake trigger wake='%s' ts=%d",
                                    rawDetected.wakeWord,
                                    rawDetected.timestamp
                                )
                                return@collect
                            }

                            if (sharedVerifier == null && config.speakerVerificationEnabled) {
                                sharedVerifier = createSharedVerifierIfEnabled()
                            }
                            val verifier = sharedVerifier
                            if (verifier != null && verifier.isReady) {
                                playPreVerificationBeep()
                                val verificationAudio = extractVerificationAudio(rawDetected)
                                if (verificationAudio.isNotEmpty()) {
                                    queueVerificationClipWavDump(rawDetected, verificationAudio)
                                    val result = verifier.verify(verificationAudio)
                                    Timber.i(
                                        "Shared speaker verification wake='%s' score=%.4f threshold=%.2f accepted=%s audioMs=%d",
                                        rawDetected.wakeWord,
                                        result.score,
                                        config.speakerVerificationThreshold,
                                        result.accepted,
                                        verificationAudio.size * 1000 / config.sampleRate
                                    )
                                    if (!result.accepted) {
                                        Timber.i(
                                            "SHARED FALSE_POSITIVE_BLOCKED wake='%s' engineScore=%.4f speakerScore=%.4f reason=%s",
                                            rawDetected.wakeWord,
                                            rawDetected.score,
                                            result.score,
                                            result.reason
                                        )
                                        return@collect
                                    }

                                    emit(
                                        WakeWordEngineProvider.AudioResult.WakeDetected(
                                            rawDetected.copy(
                                                detected = true,
                                                vadScore = result.score
                                            )
                                        )
                                    )
                                } else {
                                    // No buffered audio yet; fail open to avoid blocking valid first trigger.
                                    Timber.w("Shared speaker verification skipped: empty audio buffer")
                                    emit(WakeWordEngineProvider.AudioResult.WakeDetected(rawDetected))
                                }
                            } else {
                                emit(WakeWordEngineProvider.AudioResult.WakeDetected(rawDetected))
                            }
                        }

                        is WakeWordEngineProvider.AudioResult.StopDetected -> {
                            val detectInfo = WakeWordEngineProvider.WakeWordDetection(
                                it.detection.wakeWordId,
                                it.detection.wakeWord,
                                it.detection.detected,
                                it.detection.score,
                                timestamp = it.detection.timestamp
                            )
                            emit(WakeWordEngineProvider.AudioResult.StopDetected(detectInfo))
                        }

                        is WakeWordEngineProvider.AudioResult.Audio -> {
                            val frame = audioDsp.byteArrayToFloatArray(it.audio.toByteArray())
                            if (frame.isNotEmpty()) {
                                addVerificationAudio(frame, it.timestamp, it.scores)
                                processEnrollmentAudio(frame)
                            }
                            emit(it)
                        }

                        is WakeWordEngineProvider.AudioResult.AudioLevel -> {
                            emit(it)
                        }

                        is WakeWordEngineProvider.AudioResult.EngineStatus -> {
                            emit(it)
                        }
                    }
                }
            } finally {
                sharedVerifier?.close()
            }
        }
    }.onCompletion { cause ->
        if (cause == null) {
            emit(WakeWordEngineProvider.AudioResult.EngineStatus("Stopped"))
        } else if (cause !is CancellationException) {
            Timber.w(cause, "WakeWordEngine completed with failure")
        }
    }

    private fun addVerificationAudio(frame: FloatArray, timestamp: Long, scores: Map<String, Float>) {
        if (frame.isEmpty()) return
        synchronized(verificationRingBufferLock) {
            verificationRingBuffer.addLast(
                VerificationTraceFrame(
                    timestamp = timestamp,
                    audio = frame.copyOf(),
                    scores = HashMap(scores)
                )
            )
            trimVerificationBufferLocked(timestamp)
        }
    }

    private fun trimVerificationBufferLocked(nowMs: Long) {
        val oldestAllowed = nowMs - speakerVerificationMaxWindowMs
        while (verificationRingBuffer.isNotEmpty() && verificationRingBuffer.first().timestamp < oldestAllowed) {
            verificationRingBuffer.removeFirst()
        }
    }

    private fun createSharedVerifierIfEnabled(): SherpaSpeakerVerifier? {
        maybeEnableSpeakerVerificationFromExistingEnrollment()
        if (!config.speakerVerificationEnabled) return null
        return SherpaSpeakerVerifier(context, config).also {
            if (!it.isReady) {
                Timber.w("Shared speaker verifier unavailable; runtime verification bypassed")
            } else {
                Timber.i(
                    "Shared speaker verifier enabled (threshold=%.2f)",
                    config.speakerVerificationThreshold
                )
            }
        }
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

    private fun extractVerificationAudio(detection: WakeWordEngineProvider.WakeWordDetection): FloatArray {
        synchronized(verificationRingBufferLock) {
            if (verificationRingBuffer.isEmpty()) return FloatArray(0)
            val scoreBoundedAudio = extractScoreBoundedAudioLocked(detection)
            if (scoreBoundedAudio.isNotEmpty()) {
                Timber.i(
                    "Shared verification clip mode=score_boundary wake='%s' samples=%d ms=%d",
                    detection.wakeWord,
                    scoreBoundedAudio.size,
                    scoreBoundedAudio.size * 1000 / config.sampleRate
                )
                return scoreBoundedAudio
            }
            val fallbackAudio = extractTimestampWindowAudioLocked(detection.timestamp)
            if (fallbackAudio.isNotEmpty()) {
                Timber.i(
                    "Shared verification clip mode=timestamp_fallback wake='%s' samples=%d ms=%d",
                    detection.wakeWord,
                    fallbackAudio.size,
                    fallbackAudio.size * 1000 / config.sampleRate
                )
            }
            return fallbackAudio
        }
    }

    private fun extractTimestampWindowAudioLocked(detectionTimestampMs: Long): FloatArray {
        val minTimestamp = detectionTimestampMs - speakerVerificationPreRollMs
        val maxTimestamp = detectionTimestampMs + speakerVerificationPostRollMs

        val selected = verificationRingBuffer.filter { it.timestamp in minTimestamp..maxTimestamp }
        if (selected.isNotEmpty()) {
            return flattenFrames(selected.map { it.audio })
        }

        val latestTimestamp = verificationRingBuffer.last().timestamp
        val fallbackMinTimestamp = latestTimestamp - speakerVerificationPreRollMs
        val fallback = verificationRingBuffer.filter { it.timestamp >= fallbackMinTimestamp }
        if (fallback.isNotEmpty()) {
            return flattenFrames(fallback.map { it.audio })
        }
        return FloatArray(0)
    }

    private fun extractScoreBoundedAudioLocked(detection: WakeWordEngineProvider.WakeWordDetection): FloatArray {
        val frames = verificationRingBuffer.map { it.audio }
        val scoreFrames = verificationRingBuffer.map { it.scores }
        if (frames.isEmpty() || scoreFrames.isEmpty() || frames.size != scoreFrames.size) {
            return FloatArray(0)
        }

        val wakeKeys = listOf(detection.wakeWord, detection.wakeWordId)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (wakeKeys.isEmpty()) return FloatArray(0)

        val wakeScores = FloatArray(frames.size)
        var peakIdx = -1
        var peakScore = Float.NEGATIVE_INFINITY
        for (i in frames.indices) {
            val score = wakeKeys.maxOfOrNull { key -> scoreFrames[i][key] ?: 0f } ?: 0f
            wakeScores[i] = score
            if (score > peakScore) {
                peakScore = score
                peakIdx = i
            }
        }

        if (peakIdx < 0 || peakScore <= 0f) return FloatArray(0)

        val boundary = (peakScore * 0.25f).coerceAtLeast(0.08f)
        var lastStrongIdx = -1
        for (i in wakeScores.lastIndex downTo 0) {
            if (wakeScores[i] >= boundary) {
                lastStrongIdx = i
                break
            }
        }
        if (lastStrongIdx < 0) return FloatArray(0)

        var startIdx = lastStrongIdx
        var endIdx = lastStrongIdx
        val maxBacktrackGapFrames = 2
        var remainingGapFrames = maxBacktrackGapFrames
        while (startIdx > 0) {
            val prevScore = wakeScores[startIdx - 1]
            if (prevScore >= boundary) {
                startIdx--
                remainingGapFrames = maxBacktrackGapFrames
            } else if (remainingGapFrames > 0) {
                startIdx--
                remainingGapFrames--
            } else {
                break
            }
        }
        if (startIdx > 0) startIdx--

        val prePadSamples = (config.sampleRate * 500) / 1000
        val postPadSamples = (config.sampleRate * 40) / 1000
        val minSamples = (config.sampleRate * 450) / 1000
        val maxSamples = (config.sampleRate * speakerVerificationMaxWindowMs.toInt()) / 1000

        fun sampleCount(from: Int, to: Int): Int {
            var total = 0
            for (i in from..to) total += frames[i].size
            return total
        }

        var addedPre = 0
        while (startIdx > 0 && addedPre < prePadSamples) {
            startIdx--
            addedPre += frames[startIdx].size
        }

        var addedPost = 0
        while (endIdx < frames.lastIndex && addedPost < postPadSamples) {
            endIdx++
            addedPost += frames[endIdx].size
        }

        while (sampleCount(startIdx, endIdx) < minSamples && (startIdx > 0 || endIdx < frames.lastIndex)) {
            if (startIdx > 0) startIdx--
            if (sampleCount(startIdx, endIdx) < minSamples && endIdx < frames.lastIndex) endIdx++
        }

        while (sampleCount(startIdx, endIdx) > maxSamples && (startIdx < lastStrongIdx || endIdx > lastStrongIdx)) {
            if (endIdx > lastStrongIdx) {
                endIdx--
            } else if (startIdx < lastStrongIdx) {
                startIdx++
            } else {
                break
            }
        }

        val selected = ArrayList<FloatArray>(endIdx - startIdx + 1)
        for (i in startIdx..endIdx) selected.add(frames[i])
        if (selected.isEmpty()) return FloatArray(0)

        val flattened = flattenFrames(selected)
        val selectedMs = if (config.sampleRate > 0) {
            (flattened.size.toDouble() * 1000.0 / config.sampleRate.toDouble())
        } else 0.0

        val scoreLogStart = maxOf(0, wakeScores.size - 20)
        val scoreLog = (scoreLogStart until wakeScores.size)
            .joinToString(separator = ",") { i -> "$i:${"%.3f".format(wakeScores[i])}" }
        Timber.i(
            "Shared boundary debug wake='%s' peakScore=%.4f peakIdx=%d lastStrongIdx=%d boundary=%.4f startIdx=%d endIdx=%d frames=%d selectedSamples=%d selectedMs=%.1f scores=[%s]",
            detection.wakeWord,
            peakScore,
            peakIdx,
            lastStrongIdx,
            boundary,
            startIdx,
            endIdx,
            frames.size,
            flattened.size,
            selectedMs,
            scoreLog
        )

        return flattened
    }

    private fun flattenFrames(frames: List<FloatArray>): FloatArray {
        if (frames.isEmpty()) return FloatArray(0)
        val totalSamples = frames.sumOf { it.size }
        val out = FloatArray(totalSamples)
        var offset = 0
        frames.forEach { frame ->
            frame.copyInto(out, offset)
            offset += frame.size
        }
        return out
    }

    private fun isDuplicateWakeTrigger(detection: WakeWordEngineProvider.WakeWordDetection): Boolean {
        val wakeKey = detection.wakeWord.lowercase()
        val now = detection.timestamp
        while (wakeTriggerHistory.isNotEmpty() && now - wakeTriggerHistory.first().second > speakerVerificationMaxWindowMs) {
            wakeTriggerHistory.removeFirst()
        }
        val duplicate = wakeTriggerHistory.any { it.first == wakeKey && now - it.second < duplicateWakeSuppressionMs }
        if (!duplicate) {
            wakeTriggerHistory.addLast(wakeKey to now)
        }
        return duplicate
    }

    private fun processEnrollmentAudio(frame: FloatArray) {
        if (speakerEnrollmentRequested && enrollmentState == null) {
            beginSpeakerEnrollment()
        }

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

        enrollmentState = SpeakerEnrollmentState(targetSamples = 10)
        armEnrollmentForNextSample()
        toast("Speaker enrollment started. Wait for beep, then say '${config.wakeWord}' clearly 10 times.")
    }

    private fun armEnrollmentForNextSample() {
        val state = enrollmentState ?: return
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
        queueEnrollmentWavDump(state.utterances, config.wakeWord)

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
            val totalSamples = state.utterances.sumOf { it.size }
            val totalMs = if (config.sampleRate > 0) {
                (totalSamples.toDouble() * 1000.0 / config.sampleRate.toDouble())
            } else 0.0
            Timber.i(
                "Shared speaker enrollment complete samples=%d utterances=%d total_ms=%.1f",
                totalSamples,
                state.utterances.size,
                totalMs
            )
            toast("Speaker enrollment complete (${embeddings.size} samples)")
        } catch (t: Throwable) {
            Timber.w(t, "Speaker enrollment processing failed")
            toast("Speaker enrollment failed: processing error")
        } finally {
            runCatching { extractor.release() }
        }
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

    private fun frameRms(frame: FloatArray): Float {
        if (frame.isEmpty()) return 0f
        var sum = 0.0
        frame.forEach { sample ->
            val s = sample.toDouble()
            sum += s * s
        }
        return sqrt(sum / frame.size).toFloat()
    }

    private fun toast(message: String) {
        config.eventBroadcaster.notifyEvent(Event("showToastMessage", "", message))
    }

    private fun playPreVerificationBeep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            try {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, preVerificationBeepMs)
            } finally {
                tone.release()
            }
        }.onFailure {
            Timber.w(it, "Pre-verification beep failed")
        }
    }

    private fun queueVerificationClipWavDump(detection: WakeWordEngineProvider.WakeWordDetection, audio: FloatArray) {
        if (audio.isEmpty()) return
        val copy = audio.copyOf()
        wakeClipDumpScope.launch {
            dumpVerificationClipWav(detection, copy)
        }
    }

    private fun queueEnrollmentWavDump(utterances: List<FloatArray>, wakeWord: String) {
        if (utterances.isEmpty()) return
        val copies = utterances.map { it.copyOf() }
        val wakeLabel = wakeWord
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
            .ifEmpty { "wake" }
        wakeClipDumpScope.launch {
            dumpEnrollmentWav(wakeLabel, copies)
        }
    }

    private fun dumpEnrollmentWav(wakeLabel: String, utterances: List<FloatArray>) {
        if (utterances.isEmpty()) return
        runCatching {
            val dir = resolveWakeClipDirectory()
            val ts = System.currentTimeMillis()
            val merged = flattenFrames(utterances)
            val mergedFile = File(dir, "enroll_${wakeLabel}_${ts}_all${utterances.size}.wav")
            writePcm16Wav(mergedFile, merged, config.sampleRate)

            utterances.forEachIndexed { idx, utterance ->
                val file = File(dir, "enroll_${wakeLabel}_${ts}_u${idx + 1}.wav")
                writePcm16Wav(file, utterance, config.sampleRate)
            }

            val mergedMs = if (config.sampleRate > 0) {
                (merged.size.toDouble() * 1000.0 / config.sampleRate.toDouble()).toInt()
            } else 0
            Timber.i(
                "Saved enrollment wavs base=%s merged_samples=%d merged_ms=%d utterances=%d",
                "enroll_${wakeLabel}_${ts}",
                merged.size,
                mergedMs,
                utterances.size
            )
        }.onFailure { t ->
            Timber.w(t, "Failed to save enrollment wavs")
        }
    }

    private fun dumpVerificationClipWav(detection: WakeWordEngineProvider.WakeWordDetection, audio: FloatArray) {
        if (audio.isEmpty()) return
        runCatching {
            val dir = resolveWakeClipDirectory()
            trimWakeClipHistory(dir, maxFiles = 60)

            val safeWake = detection.wakeWord
                .lowercase()
                .replace(Regex("[^a-z0-9._-]+"), "_")
                .trim('_')
                .ifEmpty { "wake" }
            val ts = System.currentTimeMillis()
            val file = File(
                dir,
                "wake_${safeWake}_${ts}_score_${"%.3f".format(detection.score)}.wav"
            )
            writePcm16Wav(file, audio, config.sampleRate)
            Timber.i(
                "Saved shared wake clip wav path=%s samples=%d ms=%d",
                file.absolutePath,
                audio.size,
                ((audio.size.toFloat() / config.sampleRate.toFloat()) * 1000f).toInt()
            )
        }.onFailure { t ->
            Timber.w(t, "Failed to save shared wake clip wav")
        }
    }

    private fun resolveWakeClipDirectory(): File {
        @Suppress("DEPRECATION")
        val publicDownloads = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "vaca-wake-clips"
        )
        if (ensureDirectory(publicDownloads)) {
            return publicDownloads
        }

        val externalBase = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (externalBase != null) {
            val appExternalDownloads = File(externalBase, "wake-clips")
            if (ensureDirectory(appExternalDownloads)) {
                return appExternalDownloads
            }
        }

        val privateDir = File(context.filesDir, "wake-clips")
        ensureDirectory(privateDir)
        return privateDir
    }

    private fun ensureDirectory(dir: File): Boolean {
        return runCatching {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            dir.exists() && dir.isDirectory
        }.getOrElse { false }
    }

    private fun trimWakeClipHistory(dir: File, maxFiles: Int) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav", ignoreCase = true) } ?: return
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxFiles)
            .forEach { runCatching { it.delete() } }
    }

    private fun writePcm16Wav(file: File, audio: FloatArray, sampleRate: Int) {
        val numChannels = 1
        val bitsPerSample = 16
        val bytesPerSample = bitsPerSample / 8
        val dataSize = audio.size * bytesPerSample
        val byteRate = sampleRate * numChannels * bytesPerSample
        val blockAlign = numChannels * bytesPerSample
        val chunkSize = 36 + dataSize

        FileOutputStream(file).use { fos ->
            fun writeAscii(value: String) {
                fos.write(value.toByteArray(Charsets.US_ASCII))
            }
            fun writeIntLE(value: Int) {
                fos.write(value and 0xFF)
                fos.write((value ushr 8) and 0xFF)
                fos.write((value ushr 16) and 0xFF)
                fos.write((value ushr 24) and 0xFF)
            }
            fun writeShortLE(value: Int) {
                fos.write(value and 0xFF)
                fos.write((value ushr 8) and 0xFF)
            }

            writeAscii("RIFF")
            writeIntLE(chunkSize)
            writeAscii("WAVE")
            writeAscii("fmt ")
            writeIntLE(16)
            writeShortLE(1)
            writeShortLE(numChannels)
            writeIntLE(sampleRate)
            writeIntLE(byteRate)
            writeShortLE(blockAlign)
            writeShortLE(bitsPerSample)
            writeAscii("data")
            writeIntLE(dataSize)

            audio.forEach { sample ->
                val pcm = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                writeShortLE(pcm and 0xFFFF)
            }
        }
    }
}
