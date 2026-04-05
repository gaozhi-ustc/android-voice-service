package com.example.voiceassistant.audio

data class WakeEvent(
    val keyword: String,
    val confidence: Float,
    val timestamp: Long
)

/**
 * Wake word detection engine.
 * MVP: manual trigger only. Future: integrate Porcupine SDK.
 */
class WakeWordEngine {

    private var listener: ((WakeEvent) -> Unit)? = null
    @Volatile
    private var running = false

    fun start() {
        running = true
        // TODO: integrate Porcupine or other wake word engine
    }

    fun stop() {
        running = false
    }

    fun setListener(listener: (WakeEvent) -> Unit) {
        this.listener = listener
    }

    /**
     * Simulate a manual trigger (used in MVP).
     */
    fun simulateTrigger() {
        listener?.invoke(
            WakeEvent(
                keyword = "manual",
                confidence = 1.0f,
                timestamp = System.currentTimeMillis()
            )
        )
    }
}
