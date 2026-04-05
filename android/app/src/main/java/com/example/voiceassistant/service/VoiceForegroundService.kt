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
import com.example.voiceassistant.audio.RecorderManager
import com.example.voiceassistant.audio.VadController
import com.example.voiceassistant.data.prefs.AppPrefs
import com.example.voiceassistant.domain.VoiceSessionController
import com.example.voiceassistant.network.GatewayClient
import com.example.voiceassistant.tts.TtsManager
import com.example.voiceassistant.ui.MainActivity

class VoiceForegroundService : Service() {

    companion object {
        private const val TAG = "VoiceForegroundService"
        private const val CHANNEL_ID = "voice_service"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START_RECORDING = "com.example.voiceassistant.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.voiceassistant.STOP_RECORDING"
    }

    private lateinit var prefs: AppPrefs
    private lateinit var recorderManager: RecorderManager
    private lateinit var vadController: VadController
    private lateinit var gatewayClient: GatewayClient
    private lateinit var ttsManager: TtsManager

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
        startForeground(NOTIFICATION_ID, buildNotification("Voice assistant is ready"))

        prefs = AppPrefs(this)
        initializeComponents()
    }

    private fun initializeComponents() {
        recorderManager = RecorderManager()
        vadController = VadController()
        gatewayClient = GatewayClient(
            baseUrl = prefs.bridgeBaseUrl,
            bearerToken = prefs.bridgeToken,
            deviceId = prefs.deviceId
        )
        ttsManager = TtsManager(this)

        sessionController = VoiceSessionController(
            recorderManager = recorderManager,
            vadController = vadController,
            gatewayClient = gatewayClient,
            ttsManager = ttsManager
        )

        Log.d(TAG, "Components initialized (bridge=${prefs.bridgeBaseUrl}, device=${prefs.deviceId})")
    }

    fun reinitializeClient() {
        gatewayClient = GatewayClient(
            baseUrl = prefs.bridgeBaseUrl,
            bearerToken = prefs.bridgeToken,
            deviceId = prefs.deviceId
        )
        sessionController = VoiceSessionController(
            recorderManager = recorderManager,
            vadController = vadController,
            gatewayClient = gatewayClient,
            ttsManager = ttsManager
        )
        Log.d(TAG, "Client reinitialized with new settings")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> sessionController?.startManualVoiceFlow()
            ACTION_STOP_RECORDING -> sessionController?.stopRecordingManually()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        sessionController?.cancel()
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
