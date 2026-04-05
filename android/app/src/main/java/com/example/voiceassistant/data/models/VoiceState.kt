package com.example.voiceassistant.data.models

enum class VoiceState {
    IDLE,
    ARMED,
    WAKE_TRIGGERED,
    RECORDING,
    TRANSCRIBING,
    DISPATCHING,
    SPEAKING,
    ERROR
}
