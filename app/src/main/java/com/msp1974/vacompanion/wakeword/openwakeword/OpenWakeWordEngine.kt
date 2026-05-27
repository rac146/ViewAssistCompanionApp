package com.msp1974.vacompanion.wakeword.openwakeword

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import androidx.annotation.RequiresPermission
import com.google.protobuf.ByteString
import com.msp1974.vacompanion.audio.AudioDSP
import com.msp1974.vacompanion.audio.DeepFilterNetProcessor
import com.msp1974.vacompanion.audio.MicrophoneInput
import com.msp1974.vacompanion.audio.RnNoiseProcessor
import com.msp1974.vacompanion.audio.AudioAcousticCleaner
import com.msp1974.vacompanion.audio.VACAAudioFormat
import com.msp1974.vacompanion.device.DeviceCapabilitiesManager
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import com.msp1974.vacompanion.wakeword.openwakeword.audio.AudioProcessor
import com.msp1974.vacompanion.wakeword.openwakeword.ml.ModelRunner
import com.msp1974.vacompanion.wakeword.openwakeword.ml.OnnxModelRunner
import com.msp1974.vacompanion.wakeword.openwakeword.ml.TfliteModelRunner
import com.msp1974.vacompanion.wakeword.openwakeword.model.WakeWordModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedList


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
    private val detectionStates = mutableMapOf<String, OwwDetectionState>()

    private val acousticCleaner = AudioAcousticCleaner(sampleRate = VACAAudioFormat.SAMPLE_RATE_HZ, cutoffHz = 180f, reverbDecay = 0.65f)

    private var lastTriggerTimestamp = 0L

    var isEnabled = true

    private var _audioProcessor: AudioProcessor = AudioProcessor(assetManager)

    private val rnNoise = RnNoiseProcessor(
        enabled = true,
        sampleRateHz = VACAAudioFormat.SAMPLE_RATE_HZ,
        vadThreshold = config.experimentalRnNoiseVadThreshold,
        postGain = resolveRnNoisePostGain()
    )

    private val deepNet = DeepFilterNetProcessor(
        context,
        enabled = true,
        sampleRateHz = VACAAudioFormat.SAMPLE_RATE_HZ
    )

    private data class OwwDetectionState(
        val scores: ArrayDeque<Float> = ArrayDeque(),
        var consecutiveHits: Int = 0,
        var lastTriggeredAtMs: Long = 0L
    )

    private fun resolveRnNoisePostGain(): Float {
        // Aggressive test profile: RNNoise output is much quieter on this hardware,
        // so we apply substantial makeup gain after denoise for wake-word detectability.
        return (8.0f + (config.micGain.coerceAtLeast(0) * 1.2f)).coerceIn(8.0f, 24.0f)
    }

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
            val audioRingBuffer = LinkedList<FloatArray>()
            val maxBufferSize = 20
            try {
                microphoneInput.start()
                emit(AudioResult.EngineStatus("Started"))
                while (true) {
                    val audio = microphoneInput.readFloat()
                    val frameTimestamp = System.currentTimeMillis()

                    if (audio.isNotEmpty()) {

                        audioRingBuffer.addLast(audio.clone())
                        if (audioRingBuffer.size > maxBufferSize) {
                            audioRingBuffer.removeFirst()
                        }

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

                                Timber.d("Wake Word Original Score: ${detection.score}")

                                val paddingAudio = microphoneInput.readFloat()
                                if (paddingAudio.isNotEmpty()) {
                                    audioRingBuffer.addLast(paddingAudio.clone())
                                    if (audioRingBuffer.size > maxBufferSize + 1) {
                                        audioRingBuffer.removeFirst()
                                    }
                                }

                                val fullTriggerAudio = audioRingBuffer.flatMap { it.toList() }.toFloatArray()

                                val newDetect = verifyWakeWordWithNoiseCancellation(fullTriggerAudio)

                              //  if(newDetect != null) {
                                    emit(AudioResult.WakeDetected(detection))
                              //  }
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

    fun denormaliseAudioBuffer(floatBuffer: FloatArray): ShortArray {
        val shortBuffer = floatBuffer.map {
            (it * 32768.0f).coerceIn(-32768.0f, 32767.0f).toInt().toShort()
        }.toShortArray()
        return shortBuffer
    }

    fun normaliseAudioBuffer(audioBuffer: ShortArray): FloatArray {
        val floatBuffer = audioBuffer.map { (it.toFloat() / 32768.0f) }.toFloatArray()
        return floatBuffer
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

    private suspend fun verifyWakeWordWithNoiseCancellation(rawAudio: FloatArray): WakeWordDetection? {

        val shortAudioInput = denormaliseAudioBuffer(rawAudio)
        val rawShortBackup = shortAudioInput.clone()

        // 1. Run the entire 2.2-second array through DeepNet to remove hiss/hum
        //val cleanShortAudio: ShortArray = deepNet.process(shortAudioInput)
       // val cleanShortBackup = cleanShortAudio.clone()

        var bestDetection: WakeWordDetection? = null

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val targetDir = File("/sdcard/Download")
                val fallbackDir = config.context.getExternalFilesDir(null) ?: config.context.filesDir
                val outputDir = if (targetDir.exists() || targetDir.mkdirs()) targetDir else fallbackDir
                val timestamp = System.currentTimeMillis()

                val rawPath = File(outputDir, "vaca_verification_before_${timestamp}.wav").absolutePath
                val processedPath = File(outputDir, "vaca_verification_after_${timestamp}.wav").absolutePath

                // Slow disk writing happens safely here in parallel
                writePcm16Wav(rawPath, rawShortBackup, VACAAudioFormat.SAMPLE_RATE_HZ, 1)
              //  writePcm16Wav(processedPath, cleanShortBackup, VACAAudioFormat.SAMPLE_RATE_HZ, 1)

                Timber.i("Async verification clips saved: raw=%s rnnoise=%s", rawPath, processedPath)
            } catch (e: Exception) {
                Timber.e(e, "Failed to write debug WAV files in background thread")
            }
        }

       // val cleanFloatAudio = normaliseAudioBuffer(cleanShortAudio)

       // val chunkSize = 1280 // 80ms chunks
       // var maxVerificationScore = 0.0f

        // 3. Process the cleaned array in chronological 80ms chunks
//        for (i in 0 until cleanFloatAudio.size step chunkSize) {
//            val end = minOf(i + chunkSize, cleanFloatAudio.size)
//            val chunk = cleanFloatAudio.copyOfRange(i, end)
//
//            // If the last chunk is partial, pad it with zeros
//            val finalChunk = if (chunk.size < chunkSize) {
//                chunk.copyOf(chunkSize)
//            } else {
//                chunk
//            }
//
//            // Run the clean chunk through the fresh engine instance
//            val result = processAudioVerification(finalChunk)
//            if(result != null) {
//                maxVerificationScore = result.score
//                bestDetection = result;
//            }
//        }
//
//        Timber.d("DeepFilterNet Verification Finished. Clean Max Score was: $maxVerificationScore")

        return bestDetection
    }

    fun processAudioVerification(audioBuffer: FloatArray): WakeWordDetection? {
        if (!isEnabled) return null

        var highestScore = 0.0f
        var bestDetection: WakeWordDetection? = null

        // 1. Extract the audio features exactly like normal
        val audioFeatures = _audioProcessor.getAudioFeatures(audioBuffer)

        // 2. Loop through the models to get raw, unsmoothed scores
        modelProcessors.forEach { (model, processor) ->
            try {
                val rawScore = processor.process(audioFeatures)

                // Bypass all history/smoothing completely. Look at the raw output.
                if (rawScore > model.threshold) {
                    if (rawScore > highestScore) {
                        highestScore = rawScore
                        bestDetection = WakeWordDetection(
                            model.name,
                            model.name,
                            detected = true, // We force true if the raw score clears the threshold
                            score = rawScore,
                            timestamp = System.currentTimeMillis()
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e("Error processing raw verification model ${model.name} ->$e")
            }
        }
        return bestDetection
    }

    @SuppressLint("DefaultLocale")
    fun processAudio(audioBuffer: FloatArray, timestamp: Long = System.currentTimeMillis()): List<WakeWordDetection> {
        val detections = mutableListOf<WakeWordDetection>()

        if (isEnabled) {
            acousticCleaner.clean1(audioBuffer)
            val audioFeatures = _audioProcessor.getAudioFeatures(audioBuffer)
            modelProcessors.map { (model, processor) ->
                try {
                    val score = processor.process(audioFeatures)
                    if (score > model.threshold) {
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
                            cooldownMs = maxOf(detectionCooldownMs, config.experimentalMwwCooldownMs.toLong()),
                            nowMs = timestamp
                        )
                        if (shouldTrigger) {
                            detections.add(
                                WakeWordDetection(
                                    model.name,
                                    model.name,
                                    detected = true, // Hardcoded to true since the gate cleared it
                                    score = smoothedScore,
                                    timestamp = timestamp
                                )
                            )
                        }
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

    private fun updateSmoothedScore(
        modelName: String,
        score: Float,
        windowSize: Int
    ): Float {
        val state = detectionStates.getOrPut(modelName) { OwwDetectionState() }
        state.scores.addLast(score)
        while (state.scores.size > windowSize) {
            state.scores.removeFirst()
        }
        return state.scores.average().toFloat()
    }

    private fun evaluateTrigger(
        modelName: String,
        smoothedScore: Float,
        threshold: Float,
        requiredHits: Int,
        cooldownMs: Long,
        nowMs: Long
    ): Boolean {
        val state = detectionStates.getOrPut(modelName) { OwwDetectionState() }

        // 1. HARD LOCKOUT: If we are resting from a recent trigger, clear everything and exit.
        // This stops the trailing echo/frames of "Hey Jarvis" from stacking up double hits.
        val onCooldown = nowMs - state.lastTriggeredAtMs < cooldownMs
        if (onCooldown) {
            state.consecutiveHits = 0
            return false
        }

        // 2. Track consecutive high-scoring frames using the SMOOTHED score
        if (smoothedScore >= threshold) {
            state.consecutiveHits++
        } else {
            state.consecutiveHits = 0 // Instantly drops to 0 the moment speech drops below threshold
        }

        // 3. Fire exactly once when the hits threshold is met
        if (state.consecutiveHits >= requiredHits) {
            state.lastTriggeredAtMs = nowMs // Lock the gate immediately for the duration of cooldownMs
            state.consecutiveHits = 0        // Clear counter so next frame cannot re-trigger
            return true
        }

        return false
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
        detectionStates.clear()
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
