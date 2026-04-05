# 02. Android App Design

> 安卓端模块设计、状态机、权限与 Kotlin 项目骨架

---

# 1. 安卓端职责

安卓端负责：

- 前台常驻监听
- 唤醒词或手动触发
- 录音
- 语音结束判定
- ASR
- 文本过滤
- 调用 Bridge HTTP API
- TTS 播报结果

---

# 2. 推荐项目结构

```text
app/
 ├─ ui/
 │   ├─ MainActivity
 │   ├─ SettingsActivity
 │   └─ DeviceStatusView
 ├─ service/
 │   ├─ VoiceForegroundService
 │   ├─ WakeWordEngine
 │   ├─ RecorderManager
 │   ├─ VadController
 │   ├─ AsrManager
 │   ├─ GatewayClient
 │   └─ TtsManager
 ├─ domain/
 │   ├─ VoiceSessionController
 │   ├─ CommandFilter
 │   ├─ RetryPolicy
 │   └─ DeviceStateStore
 ├─ data/
 │   ├─ models/
 │   ├─ prefs/
 │   ├─ local/
 │   └─ remote/
 └─ util/
```

---

# 3. 核心模块设计

## 3.1 VoiceForegroundService

职责：

- 前台服务常驻
- 统一管理语音链路生命周期
- 开机恢复
- 保活

接口建议：

```kotlin
interface VoiceServiceController {
    fun startService()
    fun stopService()
    fun enableListening()
    fun disableListening()
    fun setMode(mode: ListenMode)
}
```

```kotlin
enum class ListenMode {
    WAKEWORD,
    MANUAL_TRIGGER,
    PUSH_TO_TALK
}
```

## 3.2 WakeWordEngine

职责：

- 持续监听唤醒词
- 触发录音

接口：

```kotlin
interface WakeWordEngine {
    fun start()
    fun stop()
    fun setListener(listener: (WakeEvent) -> Unit)
}
```

```kotlin
data class WakeEvent(
    val keyword: String,
    val confidence: Float,
    val timestamp: Long
)
```

建议：

- MVP 先用手动触发
- 正式版接 Porcupine

## 3.3 RecorderManager

职责：

- 管理麦克风采集
- 收集 PCM 数据
- 输出录音片段

接口：

```kotlin
interface RecorderManager {
    fun startRecording()
    fun stopRecording(): AudioClip
    fun isRecording(): Boolean
}
```

```kotlin
data class AudioClip(
    val pcmBytes: ByteArray,
    val sampleRate: Int,
    val channels: Int,
    val format: String,
    val durationMs: Long
)
```

建议参数：

- sampleRate = 16000
- channels = 1
- format = PCM_16BIT

## 3.4 VadController

职责：

- 判断说话开始/结束
- 自动截断录音

建议规则：

- 最大录音时长：8 秒
- 连续静音阈值：1000ms
- 最小有效语音：600ms

接口：

```kotlin
interface VadController {
    fun reset()
    fun onAudioFrame(frame: ByteArray): VadDecision
}
```

```kotlin
data class VadDecision(
    val speechStarted: Boolean,
    val speechEnded: Boolean,
    val shouldContinue: Boolean
)
```

## 3.5 AsrManager

职责：

- 本地或远程语音转文字

接口：

```kotlin
interface AsrManager {
    suspend fun transcribe(audioClip: AudioClip): AsrResult
}
```

```kotlin
data class AsrResult(
    val text: String,
    val confidence: Float?,
    val language: String?,
    val latencyMs: Long
)
```

## 3.6 CommandFilter

职责：

- 过滤空文本
- 过滤明显误识别
- 短时间去重

接口：

```kotlin
interface CommandFilter {
    fun shouldDispatch(text: String): FilterResult
}
```

```kotlin
data class FilterResult(
    val accepted: Boolean,
    val reason: String? = null,
    val normalizedText: String
)
```

## 3.7 GatewayClient

职责：

- 调用 Bridge API
- 处理超时、重试
- 返回 reply_text

接口：

```kotlin
interface GatewayClient {
    suspend fun sendCommand(req: VoiceCommandRequest): VoiceCommandResponse
}
```

## 3.8 TtsManager

职责：

- 播放回复文本
- 支持停止和打断

接口：

```kotlin
interface TtsManager {
    fun speak(text: String)
    fun stop()
}
```

## 3.9 VoiceSessionController

职责：

统一编排：

```text
wakeword/manual trigger
  -> record
  -> vad end
  -> asr
  -> filter
  -> send http
  -> tts
```

状态机：

```kotlin
enum class VoiceState {
    IDLE,
    ARMED,
    WAKE_TRIGGERED,
    RECORDING,
    TRANSCRIBING,
    DISPATCHING,
    SPEAKING,
    ERROR
}
```

---

# 4. 权限与系统要求

AndroidManifest 建议至少包含：

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

前台服务声明：

```xml
<service
    android:name=".service.VoiceForegroundService"
    android:foregroundServiceType="microphone"
    android:exported="false" />
```

额外建议：

- 提示用户关闭电池优化
- 设置开机自启
- 处理录音权限被拒情况

---

# 5. Kotlin 项目骨架

## 5.1 `MainActivity.kt`

```kotlin
package com.example.voiceassistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.example.voiceassistant.service.VoiceForegroundService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = Intent(this, VoiceForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)

        setContent {
            // TODO: 设置页面 / 状态页面
        }
    }
}
```

## 5.2 `VoiceForegroundService.kt`

```kotlin
package com.example.voiceassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.voiceassistant.R

class VoiceForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, "voice_service")
            .setContentTitle("Voice Assistant")
            .setContentText("正在监听语音服务")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO: 启动 VoiceSessionController
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "voice_service",
                "Voice Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
```

## 5.3 `RecorderManager.kt`

```kotlin
package com.example.voiceassistant.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class RecorderManager {
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private var audioRecord: AudioRecord? = null
    private var recording = false
    private val chunks = mutableListOf<ByteArray>()

    fun startRecording() {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        chunks.clear()
        audioRecord?.startRecording()
        recording = true

        Thread {
            val buffer = ByteArray(bufferSize)
            while (recording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    chunks.add(buffer.copyOf(read))
                }
            }
        }.start()
    }

    fun stopRecording(): ByteArray {
        recording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        return chunks.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
    }

    fun isRecording(): Boolean = recording
}
```

## 5.4 `VadController.kt`

```kotlin
package com.example.voiceassistant.audio

class VadController {
    fun shouldStopBySilence(pcm: ByteArray): Boolean {
        // TODO: 接入真正的 VAD 或简单能量阈值逻辑
        return false
    }
}
```

## 5.5 `AsrManager.kt`

```kotlin
package com.example.voiceassistant.asr

class AsrManager {
    suspend fun transcribe(audio: ByteArray): String {
        // TODO: 接入本地 ASR 或远程 ASR
        return "测试转写文本"
    }
}
```

## 5.6 `ApiModels.kt`

```kotlin
package com.example.voiceassistant.network

data class VoiceCommandRequest(
    val device_id: String,
    val session_target: String = "main",
    val text: String,
    val trigger_type: String = "manual",
    val trigger_word: String? = null,
    val lang: String = "zh-CN",
    val timestamp: String,
    val meta: Map<String, Any> = emptyMap()
)

data class VoiceCommandResponse(
    val ok: Boolean,
    val request_id: String,
    val message_id: String? = null,
    val reply_text: String? = null,
    val should_tts: Boolean = true,
    val tts_text: String? = null,
    val display_text: String? = null,
    val latency_ms: Int = 0
)
```

## 5.7 `GatewayClient.kt`

```kotlin
package com.example.voiceassistant.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

class GatewayClient(
    private val baseUrl: String,
    private val bearerToken: String,
    private val deviceId: String,
) {
    private val client = OkHttpClient()

    suspend fun sendCommand(text: String): String = withContext(Dispatchers.IO) {
        val bodyJson = JSONObject()
            .put("device_id", deviceId)
            .put("session_target", "main")
            .put("text", text)
            .put("trigger_type", "manual")
            .put("timestamp", java.time.OffsetDateTime.now().toString())

        val request = Request.Builder()
            .url("$baseUrl/v1/voice/command")
            .addHeader("Authorization", "Bearer $bearerToken")
            .addHeader("X-Device-Id", deviceId)
            .addHeader("X-Request-Id", UUID.randomUUID().toString())
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            json.optString("reply_text", "")
        }
    }
}
```

## 5.8 `TtsManager.kt`

```kotlin
package com.example.voiceassistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context, this)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.CHINA
        }
    }

    fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_reply")
    }

    fun stop() {
        tts.stop()
    }
}
```

## 5.9 `VoiceSessionController.kt`

```kotlin
package com.example.voiceassistant.domain

import com.example.voiceassistant.audio.RecorderManager
import com.example.voiceassistant.network.GatewayClient
import com.example.voiceassistant.tts.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VoiceSessionController(
    private val recorderManager: RecorderManager,
    private val gatewayClient: GatewayClient,
    private val ttsManager: TtsManager,
) {
    fun startManualVoiceFlow() {
        recorderManager.startRecording()

        // 示例：实际项目里应由按钮再次点击或 VAD 结束录音
        Thread.sleep(3000)
        recorderManager.stopRecording()

        CoroutineScope(Dispatchers.IO).launch {
            val recognizedText = "帮我看看今天的日程"
            val reply = gatewayClient.sendCommand(recognizedText)
            if (reply.isNotBlank()) {
                ttsManager.speak(reply)
            }
        }
    }
}
```

---

# 6. 推荐实施顺序

1. 先完成前台服务、录音、HTTP、TTS
2. 再补 VAD 和错误处理
3. 最后再接唤醒词
