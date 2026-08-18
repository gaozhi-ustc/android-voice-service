package com.example.voiceassistant.domain

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages the cooldown timer after TTS completes.
 * If no speech is detected within the timeout, fires onTimeout callback.
 */
class IdleTimeoutManager(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "IdleTimeoutManager"
        const val DEFAULT_TIMEOUT_MS = 9000L
    }

    private var timeoutJob: Job? = null
    var onTimeout: (() -> Unit)? = null

    fun startCooldown(durationMs: Long = DEFAULT_TIMEOUT_MS) {
        cancel()
        Log.d(TAG, "Starting ${durationMs}ms cooldown")
        timeoutJob = scope.launch {
            delay(durationMs)
            Log.d(TAG, "Cooldown timeout reached")
            onTimeout?.invoke()
        }
    }

    fun cancel() {
        timeoutJob?.cancel()
        timeoutJob = null
    }
}
