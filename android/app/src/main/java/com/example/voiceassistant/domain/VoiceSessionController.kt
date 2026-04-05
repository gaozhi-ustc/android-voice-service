package com.example.voiceassistant.domain

import android.util.Log
import com.example.voiceassistant.audio.RecorderManager
import com.example.voiceassistant.audio.VadController
import com.example.voiceassistant.data.models.VoiceState
import com.example.voiceassistant.network.GatewayClient
import com.example.voiceassistant.tts.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceSessionController(
    private val recorderManager: RecorderManager,
    private val vadController: VadController,
    private val gatewayClient: GatewayClient,
    private val ttsManager: TtsManager,
    private val retryPolicy: RetryPolicy = RetryPolicy()
) {
    companion object {
        private const val TAG = "VoiceSessionController"
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    @Volatile
    var currentState: VoiceState = VoiceState.IDLE
        private set

    var onStateChanged: ((VoiceState) -> Unit)? = null
    var onRecognizedText: ((String) -> Unit)? = null
    var onReplyText: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private fun setState(state: VoiceState) {
        currentState = state
        Log.d(TAG, "State -> $state")
        onStateChanged?.invoke(state)
    }

    fun startManualVoiceFlow() {
        if (currentState != VoiceState.IDLE && currentState != VoiceState.ARMED && currentState != VoiceState.ERROR) {
            Log.w(TAG, "Cannot start flow in state: $currentState")
            return
        }

        setState(VoiceState.WAKE_TRIGGERED)
        setState(VoiceState.RECORDING)

        vadController.reset()
        recorderManager.onAudioFrame = { frame ->
            val decision = vadController.onAudioFrame(frame)
            if (decision.speechEnded && !decision.shouldContinue) {
                stopRecordingAndProcess()
            }
        }
        recorderManager.startRecording()
    }

    fun stopRecordingManually() {
        if (currentState == VoiceState.RECORDING) {
            stopRecordingAndProcess()
        }
    }

    private fun stopRecordingAndProcess() {
        if (currentState != VoiceState.RECORDING) return

        recorderManager.onAudioFrame = null
        val audioClip = recorderManager.stopRecording()

        Log.d(TAG, "Recording stopped: ${audioClip.durationMs}ms, ${audioClip.pcmBytes.size} bytes")

        if (audioClip.pcmBytes.size < 3200) {
            Log.w(TAG, "Audio too short, ignoring")
            onError?.invoke("Recording too short")
            setState(VoiceState.IDLE)
            return
        }

        scope.launch {
            try {
                // Send audio to Bridge for remote ASR + execution
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
                    onError?.invoke(response.reply_text ?: "Request failed")
                    setState(VoiceState.ERROR)
                    return@launch
                }

                val replyText = response.tts_text ?: response.reply_text ?: ""
                onReplyText?.invoke(response.reply_text ?: "")

                // TTS
                if (response.should_tts && replyText.isNotBlank()) {
                    setState(VoiceState.SPEAKING)
                    ttsManager.onSpeakingDone = {
                        setState(VoiceState.IDLE)
                    }
                    ttsManager.speak(replyText)
                } else {
                    setState(VoiceState.IDLE)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Voice flow error", e)
                onError?.invoke(e.message ?: "Unknown error")
                setState(VoiceState.ERROR)
            }
        }
    }

    fun cancel() {
        if (recorderManager.isRecording()) {
            recorderManager.onAudioFrame = null
            recorderManager.stopRecording()
        }
        ttsManager.stop()
        setState(VoiceState.IDLE)
    }
}
