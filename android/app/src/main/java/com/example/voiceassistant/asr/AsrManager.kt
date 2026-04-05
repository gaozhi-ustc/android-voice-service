package com.example.voiceassistant.asr

import com.example.voiceassistant.data.models.AudioClip

data class AsrResult(
    val text: String,
    val confidence: Float?,
    val language: String?,
    val latencyMs: Long
)

class AsrManager {

    suspend fun transcribe(audioClip: AudioClip): AsrResult {
        val startTime = System.currentTimeMillis()

        // TODO: integrate local ASR (e.g. Vosk, Sherpa-ONNX) or remote ASR (e.g. Whisper API)
        // For MVP, return placeholder text
        val latency = System.currentTimeMillis() - startTime

        return AsrResult(
            text = "",
            confidence = null,
            language = "zh-CN",
            latencyMs = latency
        )
    }
}
