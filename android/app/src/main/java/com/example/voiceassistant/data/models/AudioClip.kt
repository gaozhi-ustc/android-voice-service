package com.example.voiceassistant.data.models

data class AudioClip(
    val pcmBytes: ByteArray,
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val format: String = "PCM_16BIT",
    val durationMs: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioClip) return false
        return pcmBytes.contentEquals(other.pcmBytes) &&
                sampleRate == other.sampleRate &&
                channels == other.channels &&
                format == other.format &&
                durationMs == other.durationMs
    }

    override fun hashCode(): Int {
        var result = pcmBytes.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channels
        result = 31 * result + format.hashCode()
        result = 31 * result + durationMs.hashCode()
        return result
    }
}
