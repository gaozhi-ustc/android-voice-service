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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        requestPermissionsAndStart()
    }

    private fun setupUI() {
        binding.btnRecord.setOnClickListener {
            val controller = voiceService?.sessionController ?: return@setOnClickListener

            when (controller.currentState) {
                VoiceState.IDLE, VoiceState.ARMED, VoiceState.ERROR -> {
                    controller.startManualVoiceFlow()
                }
                VoiceState.RECORDING -> {
                    controller.stopRecordingManually()
                }
                VoiceState.SPEAKING -> {
                    controller.cancel()
                }
                else -> { /* ignore during processing */ }
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateStateUI(VoiceState.IDLE)
    }

    private fun setupSessionCallbacks() {
        val controller = voiceService?.sessionController ?: return

        controller.onStateChanged = { state ->
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
            VoiceState.IDLE -> Triple(
                getString(R.string.status_idle),
                R.color.status_idle,
                getString(R.string.btn_start_recording)
            )
            VoiceState.ARMED -> Triple(
                getString(R.string.status_armed),
                R.color.status_armed,
                getString(R.string.btn_start_recording)
            )
            VoiceState.WAKE_TRIGGERED -> Triple(
                "Triggered",
                R.color.status_processing,
                getString(R.string.btn_stop_recording)
            )
            VoiceState.RECORDING -> Triple(
                getString(R.string.status_recording),
                R.color.status_recording,
                getString(R.string.btn_stop_recording)
            )
            VoiceState.TRANSCRIBING -> Triple(
                getString(R.string.status_transcribing),
                R.color.status_processing,
                "Processing..."
            )
            VoiceState.DISPATCHING -> Triple(
                getString(R.string.status_dispatching),
                R.color.status_processing,
                "Processing..."
            )
            VoiceState.SPEAKING -> Triple(
                getString(R.string.status_speaking),
                R.color.status_speaking,
                "Stop"
            )
            VoiceState.ERROR -> Triple(
                getString(R.string.status_error),
                R.color.status_error,
                getString(R.string.btn_start_recording)
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
        if (serviceBound) {
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
