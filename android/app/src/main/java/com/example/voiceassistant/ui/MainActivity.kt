package com.example.voiceassistant.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.voiceassistant.R
import com.example.voiceassistant.data.models.VoiceState
import com.example.voiceassistant.databinding.ActivityMainBinding
import com.example.voiceassistant.service.VoiceForegroundService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var voiceService: VoiceForegroundService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as VoiceForegroundService.LocalBinder
            voiceService = localBinder.getService()
            serviceBound = true
            voiceService?.startPipelineFromForeground()
            setupSessionCallbacks()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null
            serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) {
            startAndBindService()
        } else {
            Toast.makeText(this, "Audio permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private var serviceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        binding.btnRecord.setOnClickListener {
            val controller = voiceService?.sessionController ?: return@setOnClickListener

            when (controller.currentState) {
                VoiceState.LISTENING, VoiceState.COOLDOWN, VoiceState.ERROR -> {
                    controller.manualTrigger()
                }
                VoiceState.SPEAKING -> {
                    controller.cancel()
                }
                else -> { /* ignore during recording/processing */ }
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateStateUI(VoiceState.LISTENING)
    }

    private fun setupSessionCallbacks() {
        val controller = voiceService?.sessionController ?: return

        controller.addStateListener { state ->
            runOnUiThread { updateStateUI(state) }
        }

        controller.onRecognizedText = { text ->
            runOnUiThread {
                binding.tvRecognizedText.text = text
            }
        }

        controller.onReplyText = { text ->
            runOnUiThread {
                binding.tvReplyText.text = text
            }
        }

        controller.onError = { error ->
            runOnUiThread {
                binding.tvReplyText.text = "Error: $error"
            }
        }
    }

    private fun updateStateUI(state: VoiceState) {
        val (statusText, statusColor, buttonText) = when (state) {
            VoiceState.LISTENING -> Triple(
                "等待 \"你好管家\"...",
                R.color.status_idle,
                "手动触发"
            )
            VoiceState.WAKE_TRIGGERED -> Triple(
                "已唤醒",
                R.color.status_processing,
                "..."
            )
            VoiceState.RECORDING -> Triple(
                getString(R.string.status_recording),
                R.color.status_recording,
                getString(R.string.btn_stop_recording)
            )
            VoiceState.DISPATCHING -> Triple(
                getString(R.string.status_dispatching),
                R.color.status_processing,
                "处理中..."
            )
            VoiceState.SPEAKING -> Triple(
                getString(R.string.status_speaking),
                R.color.status_speaking,
                "停止"
            )
            VoiceState.COOLDOWN -> Triple(
                "等待继续...",
                R.color.status_armed,
                "手动触发"
            )
            VoiceState.ERROR -> Triple(
                getString(R.string.status_error),
                R.color.status_error,
                "手动触发"
            )
        }

        binding.tvStatus.text = statusText
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, statusColor))
        binding.btnRecord.text = buttonText
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            startAndBindService()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, VoiceForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        if (!serviceStarted) {
            serviceStarted = true
            requestPermissionsAndStart()
        } else if (serviceBound) {
            voiceService?.reinitializeClient()
            setupSessionCallbacks()
        }
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
}
