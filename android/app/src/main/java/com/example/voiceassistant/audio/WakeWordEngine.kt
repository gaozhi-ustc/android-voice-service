package com.example.voiceassistant.audio

data class WakeEvent(
    val keyword: String,
    val confidence: Float,
    val timestamp: Long
)
