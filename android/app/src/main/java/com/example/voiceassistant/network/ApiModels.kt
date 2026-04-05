package com.example.voiceassistant.network

data class VoiceCommandRequest(
    val device_id: String,
    val session_target: String = "main",
    val text: String,
    val trigger_type: String = "manual",
    val trigger_word: String? = null,
    val lang: String = "zh-CN",
    val timestamp: String,
    val meta: Map<String, Any> = emptyMap()
)

data class VoiceCommandResponse(
    val ok: Boolean,
    val request_id: String,
    val message_id: String? = null,
    val reply_text: String? = null,
    val should_tts: Boolean = true,
    val tts_text: String? = null,
    val display_text: String? = null,
    val latency_ms: Int = 0
)

data class AudioCommandResponse(
    val ok: Boolean,
    val request_id: String,
    val recognized_text: String? = null,
    val reply_text: String? = null,
    val should_tts: Boolean = true,
    val tts_text: String? = null,
    val latency_ms: Int = 0
)
