package com.example.voiceassistant.audio

import kotlin.math.abs

data class VadDecision(
    val speechStarted: Boolean,
    val speechEnded: Boolean,
    val shouldContinue: Boolean
)

class VadController {

    private val maxRecordingMs = 8000L
    private val silenceThresholdMs = 1000L
    private val minSpeechMs = 600L
    private val energyThreshold = 500

    private var recordingStartTime = 0L
    private var speechStartTime = 0L
    private var lastSpeechTime = 0L
    private var hasSpeechStarted = false

    fun reset() {
        recordingStartTime = System.currentTimeMillis()
        speechStartTime = 0L
        lastSpeechTime = 0L
        hasSpeechStarted = false
    }

    fun onAudioFrame(frame: ByteArray): VadDecision {
        val now = System.currentTimeMillis()
        val elapsed = now - recordingStartTime

        if (elapsed >= maxRecordingMs) {
            return VadDecision(
                speechStarted = hasSpeechStarted,
                speechEnded = true,
                shouldContinue = false
            )
        }

        val energy = calculateEnergy(frame)
        val isSpeech = energy > energyThreshold

        if (isSpeech) {
            lastSpeechTime = now
            if (!hasSpeechStarted) {
                hasSpeechStarted = true
                speechStartTime = now
            }
        }

        if (hasSpeechStarted && !isSpeech) {
            val silenceDuration = now - lastSpeechTime
            val speechDuration = lastSpeechTime - speechStartTime

            if (silenceDuration >= silenceThresholdMs && speechDuration >= minSpeechMs) {
                return VadDecision(
                    speechStarted = true,
                    speechEnded = true,
                    shouldContinue = false
                )
            }
        }

        return VadDecision(
            speechStarted = hasSpeechStarted,
            speechEnded = false,
            shouldContinue = true
        )
    }

    private fun calculateEnergy(frame: ByteArray): Int {
        if (frame.size < 2) return 0
        var sum = 0L
        val samples = frame.size / 2
        for (i in 0 until samples) {
            val low = frame[i * 2].toInt() and 0xFF
            val high = frame[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            sum += abs(sample)
        }
        return (sum / samples).toInt()
    }
}
