package com.example.voiceassistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Receives late-reply pushes from the bridge (adb broadcast) and hands the
 * text to the foreground service for ad-hoc TTS playback.
 */
class SpeakReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        val serviceIntent = Intent(context, VoiceForegroundService::class.java).apply {
            action = VoiceForegroundService.ACTION_SPEAK
            putExtra("text", text)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
