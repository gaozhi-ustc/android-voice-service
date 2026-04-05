package com.example.voiceassistant.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.voiceassistant.data.prefs.AppPrefs
import com.example.voiceassistant.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.etBridgeUrl.setText(prefs.bridgeBaseUrl)
        binding.etBridgeToken.setText(prefs.bridgeToken)
        binding.etDeviceId.setText(prefs.deviceId)

        binding.btnSave.setOnClickListener {
            val url = binding.etBridgeUrl.text?.toString()?.trim() ?: ""
            val token = binding.etBridgeToken.text?.toString()?.trim() ?: ""
            val deviceId = binding.etDeviceId.text?.toString()?.trim() ?: ""

            if (url.isBlank() || token.isBlank() || deviceId.isBlank()) {
                Toast.makeText(this, "All fields are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.bridgeBaseUrl = url
            prefs.bridgeToken = token
            prefs.deviceId = deviceId

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
