package com.example.voiceassistant.data.models

enum class VoiceState {
    LISTENING,
    WAKE_TRIGGERED,
    RECORDING,
    DISPATCHING,
    SPEAKING,
    COOLDOWN,
    ERROR
}
