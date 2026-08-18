package com.example.voiceassistant.domain

import android.util.Log
import com.example.voiceassistant.audio.AudioRouter
import com.example.voiceassistant.audio.RecorderManager
import com.example.voiceassistant.audio.VadController
import com.example.voiceassistant.audio.VoskWakeWordEngine
import com.example.voiceassistant.data.models.VoiceState
import com.example.voiceassistant.network.GatewayClient
import com.example.voiceassistant.tts.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceSessionController(
    private val audioRouter: AudioRouter,
    private val recorderManager: RecorderManager,
    private val vadController: VadController,
    private val wakeWordEngine: VoskWakeWordEngine,
    private val gatewayClient: GatewayClient,
    private val ttsManager: TtsManager,
    private val bargeInMonitor: BargeInMonitor,
    private val idleTimeoutManager: IdleTimeoutManager,
    private val retryPolicy: RetryPolicy = RetryPolicy()
) {
    companion object {
        private const val TAG = "VoiceSession"
        private const val COOLDOWN_ENERGY_THRESHOLD = 800
        private const val COOLDOWN_SPEECH_MS = 400L
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    @Volatile
    var currentState: VoiceState = VoiceState.LISTENING
        private set

    private val stateListeners = mutableListOf<(VoiceState) -> Unit>()

    fun addStateListener(listener: (VoiceState) -> Unit) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: (VoiceState) -> Unit) {
        stateListeners.remove(listener)
    }
    var onRecognizedText: ((String) -> Unit)? = null
    var onReplyText: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val vadFrameConsumer: (ByteArray) -> Unit = { frame ->
        val decision = vadController.onAudioFrame(frame)
        if (decision.speechEnded && !decision.shouldContinue) {
            stopRecordingAndProcess()
        }
    }

    private val bargeInFrameConsumer: (ByteArray) -> Unit = { frame ->
        bargeInMonitor.onAudioFrame(frame)
    }

    // Energy-based speech detection for COOLDOWN state
    private val cooldownFrameConsumer: (ByteArray) -> Unit = { frame ->
        val energy = calculateEnergy(frame)
        if (energy > COOLDOWN_ENERGY_THRESHOLD) {
            if (!cooldownSpeechDetected) {
                cooldownSpeechDetected = true
                cooldownSpeechStartTime = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - cooldownSpeechStartTime >= COOLDOWN_SPEECH_MS) {
                // User started speaking during cooldown — go to recording
                Log.d(TAG, "Speech detected during cooldown, starting recording")
                cooldownSpeechDetected = false
                startRecording()
            }
        } else {
            cooldownSpeechDetected = false
            cooldownSpeechStartTime = 0L
        }
    }

    @Volatile private var cooldownSpeechDetected = false
    @Volatile private var cooldownSpeechStartTime = 0L

    private fun setState(state: VoiceState) {
        currentState = state
        Log.d(TAG, "State -> $state")
        stateListeners.forEach { it(state) }
    }

    /**
     * Start always-on listening. Called once from service onCreate.
     */
    fun startAlwaysOnListening() {
        setupCallbacks()
        enterListeningState()
    }

    private fun setupCallbacks() {
        wakeWordEngine.setListener { event ->
            Log.d(TAG, "Wake word detected: ${event.keyword} (conf=${event.confidence})")
            onWakeWordDetected()
        }

        bargeInMonitor.onBargeIn = {
            Log.d(TAG, "Barge-in triggered")
            scope.launch { onBargeIn() }
        }

        idleTimeoutManager.onTimeout = {
            Log.d(TAG, "Cooldown timeout, returning to listening")
            enterListeningState()
        }
    }

    private fun enterListeningState() {
        audioRouter.clearConsumers()
        setState(VoiceState.LISTENING)
        wakeWordEngine.start()
        audioRouter.addConsumer(wakeWordEngine.frameConsumer)
    }

    private fun onWakeWordDetected() {
        audioRouter.clearConsumers()
        wakeWordEngine.stop()
        setState(VoiceState.WAKE_TRIGGERED)

        // Play cached "请讲" prompt (instant from local file), then start recording
        ttsManager.speakPrompt("请讲") {
            if (currentState == VoiceState.WAKE_TRIGGERED) {
                startRecording()
            }
        }
    }

    private fun startRecording() {
        audioRouter.clearConsumers()
        idleTimeoutManager.cancel()
        bargeInMonitor.disable()

        setState(VoiceState.RECORDING)
        vadController.reset()
        recorderManager.startAccumulating()

        audioRouter.addConsumer(recorderManager.frameConsumer)
        audioRouter.addConsumer(vadFrameConsumer)
    }

    private fun stopRecordingAndProcess() {
        if (currentState != VoiceState.RECORDING) return

        audioRouter.clearConsumers()
        val audioClip = recorderManager.stopAccumulating()

        Log.d(TAG, "Recording stopped: ${audioClip.durationMs}ms, ${audioClip.pcmBytes.size} bytes")

        if (audioClip.pcmBytes.size < 3200) {
            Log.w(TAG, "Audio too short, ignoring")
            onError?.invoke("录音太短")
            enterCooldownState()
            return
        }

        scope.launch {
            try {
                setState(VoiceState.DISPATCHING)
                val response = withContext(Dispatchers.IO) {
                    retryPolicy.execute {
                        gatewayClient.sendAudioCommand(audioClip)
                    }
                }

                val recognizedText = response.recognized_text ?: ""
                Log.d(TAG, "Recognized: '$recognizedText'")
                onRecognizedText?.invoke(recognizedText)

                if (!response.ok) {
                    Log.e(TAG, "Bridge returned error: ${response.reply_text}")
                    onError?.invoke(response.reply_text ?: "请求失败")
                    enterErrorState()
                    return@launch
                }

                val replyText = response.tts_text ?: response.reply_text ?: ""
                onReplyText?.invoke(response.reply_text ?: "")

                if (response.should_tts && replyText.isNotBlank()) {
                    enterSpeakingState(replyText)
                } else {
                    enterCooldownState()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Voice flow error", e)
                onError?.invoke(e.message ?: "未知错误")
                enterErrorState()
            }
        }
    }

    private fun enterSpeakingState(text: String) {
        setState(VoiceState.SPEAKING)

        // Enable barge-in monitoring during TTS playback
        bargeInMonitor.enable()
        audioRouter.addConsumer(bargeInFrameConsumer)

        ttsManager.onSpeakingDone = {
            bargeInMonitor.disable()
            audioRouter.removeConsumer(bargeInFrameConsumer)
            enterCooldownState()
        }
        ttsManager.speak(text)
    }

    private fun onBargeIn() {
        ttsManager.stop()
        ttsManager.onSpeakingDone = null
        bargeInMonitor.disable()
        audioRouter.clearConsumers()
        startRecording()
    }

    private fun enterCooldownState() {
        audioRouter.clearConsumers()
        setState(VoiceState.COOLDOWN)
        cooldownSpeechDetected = false
        cooldownSpeechStartTime = 0L
        audioRouter.addConsumer(cooldownFrameConsumer)
        idleTimeoutManager.startCooldown()
    }

    private fun enterErrorState() {
        audioRouter.clearConsumers()
        setState(VoiceState.ERROR)
        scope.launch {
            delay(2000)
            enterListeningState()
        }
    }

    /**
     * Manual trigger for debug — simulates wake word detection.
     */
    fun manualTrigger() {
        if (currentState == VoiceState.LISTENING || currentState == VoiceState.COOLDOWN || currentState == VoiceState.ERROR) {
            idleTimeoutManager.cancel()
            onWakeWordDetected()
        }
    }

    fun cancel() {
        audioRouter.clearConsumers()
        recorderManager.stopAccumulating()
        ttsManager.stop()
        bargeInMonitor.disable()
        idleTimeoutManager.cancel()
        enterListeningState()
    }

    private fun calculateEnergy(frame: ByteArray): Int {
        if (frame.size < 2) return 0
        var sum = 0L
        val samples = frame.size / 2
        for (i in 0 until samples) {
            val low = frame[i * 2].toInt() and 0xFF
            val high = frame[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            sum += kotlin.math.abs(sample)
        }
        return (sum / samples).toInt()
    }

}
