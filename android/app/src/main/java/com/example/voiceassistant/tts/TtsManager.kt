package com.example.voiceassistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TtsManager"
    }

    private val tts = TextToSpeech(context.applicationContext, this)
    private var initialized = false
    var onSpeakingDone: (() -> Unit)? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.CHINA)
            initialized = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!initialized) {
                Log.w(TAG, "Chinese TTS not available, falling back to default")
                tts.setLanguage(Locale.getDefault())
                initialized = true
            }

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    onSpeakingDone?.invoke()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onSpeakingDone?.invoke()
                }
            })

            Log.d(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    fun speak(text: String) {
        if (!initialized) {
            Log.w(TAG, "TTS not initialized, skipping speak")
            onSpeakingDone?.invoke()
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_reply")
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
