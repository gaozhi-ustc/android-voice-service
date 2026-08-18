package com.example.voiceassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.voiceassistant.R
import com.example.voiceassistant.audio.AudioRouter
import com.example.voiceassistant.audio.RecorderManager
import com.example.voiceassistant.audio.VadController
import com.example.voiceassistant.audio.VoskWakeWordEngine
import com.example.voiceassistant.data.models.VoiceState
import com.example.voiceassistant.data.prefs.AppPrefs
import com.example.voiceassistant.domain.BargeInMonitor
import com.example.voiceassistant.domain.IdleTimeoutManager
import com.example.voiceassistant.domain.VoiceSessionController
import com.example.voiceassistant.network.GatewayClient
import com.example.voiceassistant.tts.TtsManager
import com.example.voiceassistant.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceForegroundService : Service() {

    companion object {
        private const val TAG = "VoiceForegroundService"
        private const val CHANNEL_ID = "voice_service"
        private const val NOTIFICATION_ID = 1

        const val ACTION_MANUAL_TRIGGER = "com.example.voiceassistant.MANUAL_TRIGGER"
        const val ACTION_SPEAK = "com.example.voiceassistant.SPEAK"
    }

    private lateinit var prefs: AppPrefs
    private lateinit var audioRouter: AudioRouter
    private lateinit var recorderManager: RecorderManager
    private lateinit var vadController: VadController
    private lateinit var wakeWordEngine: VoskWakeWordEngine
    private lateinit var gatewayClient: GatewayClient
    private lateinit var ttsManager: TtsManager
    private lateinit var bargeInMonitor: BargeInMonitor
    private lateinit var idleTimeoutManager: IdleTimeoutManager

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    var sessionController: VoiceSessionController? = null
        private set

    inner class LocalBinder : Binder() {
        fun getService(): VoiceForegroundService = this@VoiceForegroundService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification("正在初始化..."))
            Log.d(TAG, "startForeground succeeded")
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed (MIUI restriction?), continuing as bound service", e)
        }

        prefs = AppPrefs(this)
        initializeComponents()
    }

    private fun initializeComponents() {
        audioRouter = AudioRouter()
        recorderManager = RecorderManager()
        vadController = VadController()
        wakeWordEngine = VoskWakeWordEngine(this)
        bargeInMonitor = BargeInMonitor()
        idleTimeoutManager = IdleTimeoutManager(serviceScope)

        gatewayClient = GatewayClient(
            baseUrl = prefs.bridgeBaseUrl,
            bearerToken = prefs.bridgeToken,
            deviceId = prefs.deviceId
        )
        ttsManager = TtsManager(this)

        sessionController = VoiceSessionController(
            audioRouter = audioRouter,
            recorderManager = recorderManager,
            vadController = vadController,
            wakeWordEngine = wakeWordEngine,
            gatewayClient = gatewayClient,
            ttsManager = ttsManager,
            bargeInMonitor = bargeInMonitor,
            idleTimeoutManager = idleTimeoutManager
        )

        // Update notification based on state changes
        sessionController?.addStateListener { state ->
            updateNotification(state)
        }

        // Poll the bridge for late replies over WiFi (replaces USB/adb push)
        startLateReplyPolling()

        // Pre-load Vosk model and TTS prompts asynchronously
        wakeWordEngine.onModelProgress = { msg ->
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(msg))
        }
        serviceScope.launch {
            withContext(Dispatchers.IO) {
                wakeWordEngine.initModel()
                ttsManager.preloadPrompts()
            }
            if (wakeWordEngine.isModelReady()) {
                Log.d(TAG, "Vosk model loaded")
            } else {
                Log.e(TAG, "Vosk model failed to load")
            }
        }

        Log.d(TAG, "Components initialized (bridge=${prefs.bridgeBaseUrl}, device=${prefs.deviceId})")
    }

    @Volatile
    private var pipelineStarted = false

    /**
     * Called by Activity when it is confirmed in the foreground.
     * Starts AudioRouter and the listening pipeline.
     */
    fun startPipelineFromForeground() {
        if (pipelineStarted) return
        pipelineStarted = true

        Log.d(TAG, "Starting pipeline from foreground activity")
        audioRouter.start()

        serviceScope.launch {
            // Wait for Vosk model if not yet loaded
            while (!wakeWordEngine.isModelReady()) {
                Log.d(TAG, "Waiting for Vosk model...")
                delay(500)
            }
            Log.d(TAG, "Starting always-on listening")
            sessionController?.startAlwaysOnListening()
        }
    }

    // --- Late reply polling (WiFi) ---

    private var lateReplyPollingStarted = false

    private fun startLateReplyPolling() {
        if (lateReplyPollingStarted) return
        lateReplyPollingStarted = true
        Log.d(TAG, "Starting late reply polling (every 1s)")
        serviceScope.launch {
            while (true) {
                delay(1000)
                try {
                    val text = gatewayClient.pollReply()
                    if (!text.isNullOrBlank()) {
                        Log.d(TAG, "Late reply received via poll: $text")
                        speakAdHoc(text)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Late reply poll failed: ${e.message}")
                }
            }
        }
    }

    fun reinitializeClient() {
        gatewayClient = GatewayClient(
            baseUrl = prefs.bridgeBaseUrl,
            bearerToken = prefs.bridgeToken,
            deviceId = prefs.deviceId
        )

        val oldController = sessionController
        sessionController = VoiceSessionController(
            audioRouter = audioRouter,
            recorderManager = recorderManager,
            vadController = vadController,
            wakeWordEngine = wakeWordEngine,
            gatewayClient = gatewayClient,
            ttsManager = ttsManager,
            bargeInMonitor = bargeInMonitor,
            idleTimeoutManager = idleTimeoutManager
        )

        sessionController?.addStateListener { state ->
            updateNotification(state)
        }

        oldController?.cancel()
        sessionController?.startAlwaysOnListening()

        Log.d(TAG, "Client reinitialized with new settings")
    }

    private fun updateNotification(state: VoiceState) {
        val text = when (state) {
            VoiceState.LISTENING -> "等待唤醒词 \"你好管家\"..."
            VoiceState.WAKE_TRIGGERED -> "已唤醒"
            VoiceState.RECORDING -> "正在录音..."
            VoiceState.DISPATCHING -> "正在处理..."
            VoiceState.SPEAKING -> "正在播报..."
            VoiceState.COOLDOWN -> "等待继续对话..."
            VoiceState.ERROR -> "出错了，即将恢复..."
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MANUAL_TRIGGER -> sessionController?.manualTrigger()
            ACTION_SPEAK -> {
                val text = intent.getStringExtra("text")
                if (!text.isNullOrBlank()) speakAdHoc(text)
            }
        }
        return START_STICKY
    }

    /**
     * Speak a late reply without disturbing the voice session state machine.
     * Retries while the session is in an audio-busy state.
     */
    private fun speakAdHoc(text: String, attempt: Int = 0) {
        val state = sessionController?.currentState
        if (state == VoiceState.SPEAKING || state == VoiceState.RECORDING ||
            state == VoiceState.DISPATCHING || state == VoiceState.WAKE_TRIGGERED
        ) {
            if (attempt < 10) {
                Log.d(TAG, "Ad-hoc TTS deferred (state=$state, attempt=$attempt)")
                serviceScope.launch {
                    delay(2000)
                    speakAdHoc(text, attempt + 1)
                }
            }
            return
        }
        Log.d(TAG, "Ad-hoc TTS: $text")
        ttsManager.speakAdHoc(text)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        sessionController?.cancel()
        audioRouter.stop()
        wakeWordEngine.shutdown()
        ttsManager.shutdown()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Voice assistant foreground service"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Assistant")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
