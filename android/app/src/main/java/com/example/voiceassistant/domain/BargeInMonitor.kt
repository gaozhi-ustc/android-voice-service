package com.example.voiceassistant.domain

import android.util.Log
import kotlin.math.abs

/**
 * Monitors audio energy during TTS playback to detect barge-in (user interrupting).
 * Uses elevated energy threshold to avoid false triggers from speaker echo.
 */
class BargeInMonitor {

    companion object {
        private const val TAG = "BargeInMonitor"
        private const val BARGE_IN_ENERGY_THRESHOLD = 2500
        private const val SUSTAINED_SPEECH_MS = 300L
    }

    @Volatile
    private var enabled = false
    private var speechStartTime = 0L
    private var hasSpeechStarted = false

    var onBargeIn: (() -> Unit)? = null

    fun reset() {
        speechStartTime = 0L
        hasSpeechStarted = false
    }

    fun enable() {
        reset()
        enabled = true
        Log.d(TAG, "Barge-in monitoring enabled")
    }

    fun disable() {
        enabled = false
        reset()
    }

    fun onAudioFrame(frame: ByteArray) {
        if (!enabled) return

        val energy = calculateEnergy(frame)
        val now = System.currentTimeMillis()

        if (energy > BARGE_IN_ENERGY_THRESHOLD) {
            if (!hasSpeechStarted) {
                hasSpeechStarted = true
                speechStartTime = now
            } else if (now - speechStartTime >= SUSTAINED_SPEECH_MS) {
                Log.d(TAG, "Barge-in detected! energy=$energy")
                enabled = false
                onBargeIn?.invoke()
            }
        } else {
            hasSpeechStarted = false
            speechStartTime = 0L
        }
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
