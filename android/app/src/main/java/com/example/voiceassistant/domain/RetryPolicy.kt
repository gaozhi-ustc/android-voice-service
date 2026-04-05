package com.example.voiceassistant.domain

import kotlinx.coroutines.delay

class RetryPolicy(
    private val maxRetries: Int = 2,
    private val initialDelayMs: Long = 500,
    private val backoffMultiplier: Double = 2.0
) {
    suspend fun <T> execute(block: suspend () -> T): T {
        var lastException: Exception? = null
        var currentDelay = initialDelayMs

        repeat(maxRetries + 1) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    delay(currentDelay)
                    currentDelay = (currentDelay * backoffMultiplier).toLong()
                }
            }
        }

        throw lastException ?: RuntimeException("Retry failed")
    }
}
