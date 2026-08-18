package com.example.voiceassistant.audio

import com.example.voiceassistant.data.models.AudioClip

/**
 * Accumulates PCM audio frames from AudioRouter into an AudioClip.
 * No longer owns AudioRecord — frames are fed externally.
 */
class RecorderManager {

    private val chunks = mutableListOf<ByteArray>()
    @Volatile
    private var accumulating = false
    private var startTimeMs: Long = 0

    val frameConsumer: (ByteArray) -> Unit = { frame ->
        if (accumulating) {
            synchronized(chunks) {
                chunks.add(frame)
            }
        }
    }

    fun startAccumulating() {
        synchronized(chunks) {
            chunks.clear()
        }
        startTimeMs = System.currentTimeMillis()
        accumulating = true
    }

    fun stopAccumulating(): AudioClip {
        accumulating = false
        val durationMs = System.currentTimeMillis() - startTimeMs
        val pcmBytes = synchronized(chunks) {
            chunks.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        }

        return AudioClip(
            pcmBytes = pcmBytes,
            sampleRate = AudioRouter.SAMPLE_RATE,
            channels = 1,
            format = "PCM_16BIT",
            durationMs = durationMs
        )
    }

    fun isAccumulating(): Boolean = accumulating
}
