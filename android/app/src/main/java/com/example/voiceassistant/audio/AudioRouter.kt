package com.example.voiceassistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Owns the single AudioRecord instance and routes PCM frames
 * to registered consumers based on current voice state.
 */
class AudioRouter {

    companion object {
        private const val TAG = "AudioRouter"
        const val SAMPLE_RATE = 16000
        private const val RETRY_DELAY_MS = 2000L
    }

    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var running = false
    private var recordingThread: Thread? = null

    private val consumers = mutableListOf<(ByteArray) -> Unit>()
    private val consumersLock = Any()

    fun start() {
        if (running) return
        Log.d(TAG, "Starting AudioRouter")
        running = true

        recordingThread = Thread {
            // Try to create AudioRecord, retry if not available (MIUI bg restriction)
            while (running && audioRecord == null) {
                audioRecord = tryCreateAudioRecord()
                if (audioRecord == null) {
                    Log.d(TAG, "AudioRecord not available yet, retrying in ${RETRY_DELAY_MS}ms...")
                    Thread.sleep(RETRY_DELAY_MS)
                }
            }
            if (!running) return@Thread

            audioRecord?.startRecording()
            Log.d(TAG, "AudioRecord started successfully")

            var frameCount = 0L
            val buffer = ByteArray(bufferSize)
            while (running) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    frameCount++
                    if (frameCount % 200 == 0L) {
                        synchronized(consumersLock) {
                            Log.d(TAG, "Frame #$frameCount, ${consumers.size} consumers, read=$read bytes")
                        }
                    }
                    val frame = buffer.copyOf(read)
                    synchronized(consumersLock) {
                        for (consumer in consumers) {
                            try {
                                consumer(frame)
                            } catch (e: Exception) {
                                Log.e(TAG, "Consumer error", e)
                            }
                        }
                    }
                }
            }
        }.apply {
            name = "AudioRouter"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryCreateAudioRecord(): AudioRecord? {
        // Try VOICE_COMMUNICATION first (hardware echo cancellation)
        try {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                channelConfig,
                audioFormat,
                bufferSize
            )
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "Using VOICE_COMMUNICATION audio source")
                return rec
            }
            rec.release()
        } catch (e: Exception) {
            Log.w(TAG, "VOICE_COMMUNICATION not available: ${e.message}")
        }

        // Fall back to MIC
        try {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channelConfig,
                audioFormat,
                bufferSize
            )
            Log.d(TAG, "MIC AudioRecord state=${rec.state} (need ${AudioRecord.STATE_INITIALIZED}), bufferSize=$bufferSize")
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "Using MIC audio source")
                return rec
            }
            rec.release()
        } catch (e: Exception) {
            Log.w(TAG, "MIC not available: ${e.message}")
        }

        return null
    }

    fun stop() {
        Log.d(TAG, "Stopping AudioRouter")
        running = false
        recordingThread?.join(3000)
        recordingThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null
    }

    fun addConsumer(consumer: (ByteArray) -> Unit) {
        synchronized(consumersLock) {
            consumers.add(consumer)
        }
    }

    fun removeConsumer(consumer: (ByteArray) -> Unit) {
        synchronized(consumersLock) {
            consumers.remove(consumer)
        }
    }

    fun clearConsumers() {
        synchronized(consumersLock) {
            consumers.clear()
        }
    }
}
