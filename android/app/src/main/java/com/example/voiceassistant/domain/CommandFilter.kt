package com.example.voiceassistant.domain

data class FilterResult(
    val accepted: Boolean,
    val reason: String? = null,
    val normalizedText: String
)

class CommandFilter {

    private val recentCommands = mutableListOf<Pair<String, Long>>()
    private val dedupeWindowMs = 3000L
    private val minTextLength = 1

    fun shouldDispatch(text: String): FilterResult {
        val trimmed = text.trim()

        if (trimmed.length < minTextLength) {
            return FilterResult(
                accepted = false,
                reason = "text too short",
                normalizedText = trimmed
            )
        }

        val now = System.currentTimeMillis()
        synchronized(recentCommands) {
            recentCommands.removeAll { now - it.second > dedupeWindowMs }

            if (recentCommands.any { it.first == trimmed }) {
                return FilterResult(
                    accepted = false,
                    reason = "duplicate within ${dedupeWindowMs}ms",
                    normalizedText = trimmed
                )
            }

            recentCommands.add(trimmed to now)
        }

        return FilterResult(
            accepted = true,
            normalizedText = trimmed
        )
    }
}
