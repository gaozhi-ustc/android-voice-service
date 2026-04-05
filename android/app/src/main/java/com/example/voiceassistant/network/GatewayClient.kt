package com.example.voiceassistant.network

import android.util.Log
import com.example.voiceassistant.data.models.AudioClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

class GatewayClient(
    private val baseUrl: String,
    private val bearerToken: String,
    private val deviceId: String
) {
    companion object {
        private const val TAG = "GatewayClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun sendCommand(text: String, triggerType: String = "manual"): VoiceCommandResponse =
        withContext(Dispatchers.IO) {
            val requestId = UUID.randomUUID().toString()

            val bodyJson = JSONObject().apply {
                put("device_id", deviceId)
                put("session_target", "main")
                put("text", text)
                put("trigger_type", triggerType)
                put("lang", "zh-CN")
                put("timestamp", OffsetDateTime.now().toString())
                put("meta", JSONObject().apply {
                    put("app_version", "1.0.0")
                })
            }

            val request = Request.Builder()
                .url("$baseUrl/v1/voice/command")
                .addHeader("Authorization", "Bearer $bearerToken")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Device-Id", deviceId)
                .addHeader("X-Request-Id", requestId)
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            Log.d(TAG, "Sending command: $text (request_id=$requestId)")

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Request failed: ${response.code} - $body")
                    return@withContext VoiceCommandResponse(
                        ok = false,
                        request_id = requestId,
                        reply_text = "Request failed: ${response.code}"
                    )
                }

                val json = JSONObject(body)
                Log.d(TAG, "Response: $body")

                VoiceCommandResponse(
                    ok = json.optBoolean("ok", false),
                    request_id = json.optString("request_id", requestId),
                    message_id = json.optString("message_id", null),
                    reply_text = json.optString("reply_text", ""),
                    should_tts = json.optBoolean("should_tts", true),
                    tts_text = json.optString("tts_text", null),
                    display_text = json.optString("display_text", null),
                    latency_ms = json.optInt("latency_ms", 0)
                )
            }
        }

    suspend fun sendAudioCommand(audioClip: AudioClip, triggerType: String = "manual"): AudioCommandResponse =
        withContext(Dispatchers.IO) {
            val requestId = UUID.randomUUID().toString()
            val wavBytes = pcmToWav(audioClip.pcmBytes, audioClip.sampleRate, audioClip.channels)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("session_target", "main")
                .addFormDataPart("trigger_type", triggerType)
                .addFormDataPart("timestamp", OffsetDateTime.now().toString())
                .addFormDataPart(
                    "audio_file", "recording.wav",
                    wavBytes.toRequestBody("audio/wav".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("$baseUrl/v1/voice/audio-command")
                .addHeader("Authorization", "Bearer $bearerToken")
                .addHeader("X-Device-Id", deviceId)
                .addHeader("X-Request-Id", requestId)
                .post(requestBody)
                .build()

            Log.d(TAG, "Sending audio command: ${audioClip.durationMs}ms, ${wavBytes.size} bytes (request_id=$requestId)")

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Audio command failed: ${response.code} - $body")
                    return@withContext AudioCommandResponse(
                        ok = false,
                        request_id = requestId,
                        reply_text = "Request failed: ${response.code}"
                    )
                }

                val json = JSONObject(body)
                Log.d(TAG, "Audio command response: $body")

                AudioCommandResponse(
                    ok = json.optBoolean("ok", false),
                    request_id = json.optString("request_id", requestId),
                    recognized_text = json.optString("recognized_text", ""),
                    reply_text = json.optString("reply_text", ""),
                    should_tts = json.optBoolean("should_tts", true),
                    tts_text = json.optString("tts_text", null),
                    latency_ms = json.optInt("latency_ms", 0)
                )
            }
        }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val totalSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)
        // RIFF header
        out.write("RIFF".toByteArray())
        out.write(intToLittleEndian(totalSize))
        out.write("WAVE".toByteArray())
        // fmt chunk
        out.write("fmt ".toByteArray())
        out.write(intToLittleEndian(16))
        out.write(shortToLittleEndian(1)) // PCM
        out.write(shortToLittleEndian(channels))
        out.write(intToLittleEndian(sampleRate))
        out.write(intToLittleEndian(byteRate))
        out.write(shortToLittleEndian(blockAlign))
        out.write(shortToLittleEndian(bitsPerSample))
        // data chunk
        out.write("data".toByteArray())
        out.write(intToLittleEndian(dataSize))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun intToLittleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 24 and 0xFF).toByte()
    )

    private fun shortToLittleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte()
    )
}
