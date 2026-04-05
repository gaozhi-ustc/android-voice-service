package com.example.voiceassistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.voiceassistant.data.models.AudioClip

class RecorderManager {

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var recording = false
    private val chunks = mutableListOf<ByteArray>()
    private var recordingThread: Thread? = null
    private var startTimeMs: Long = 0

    var onAudioFrame: ((ByteArray) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (recording) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        chunks.clear()
        startTimeMs = System.currentTimeMillis()
        audioRecord?.startRecording()
        recording = true

        recordingThread = Thread {
            val buffer = ByteArray(bufferSize)
            while (recording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val chunk = buffer.copyOf(read)
                    synchronized(chunks) {
                        chunks.add(chunk)
                    }
                    onAudioFrame?.invoke(chunk)
                }
            }
        }.apply {
            name = "AudioRecorder"
            start()
        }
    }

    fun stopRecording(): AudioClip {
        recording = false
        recordingThread?.join(1000)
        recordingThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val durationMs = System.currentTimeMillis() - startTimeMs
        val pcmBytes = synchronized(chunks) {
            chunks.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        }

        return AudioClip(
            pcmBytes = pcmBytes,
            sampleRate = sampleRate,
            channels = 1,
            format = "PCM_16BIT",
            durationMs = durationMs
        )
    }

    fun isRecording(): Boolean = recording
}
