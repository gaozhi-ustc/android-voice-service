package com.example.voiceassistant.tts

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.net.URLEncoder
import java.util.Locale

class TtsManager(private val appContext: Context) {

    companion object {
        private const val TAG = "TtsManager"
    }

    private var tts: TextToSpeech? = null
    private var nativeTtsReady = false
    private var mediaPlayer: MediaPlayer? = null
    var onSpeakingDone: (() -> Unit)? = null

    init {
        // Try native TTS
        tts = TextToSpeech(appContext, { status ->
            if (status == TextToSpeech.SUCCESS) {
                setupNativeTts()
            } else {
                Log.w(TAG, "Native TTS not available (status=$status), will use online TTS fallback")
            }
        })
    }

    private fun setupNativeTts() {
        val engine = tts ?: return
        val result = engine.setLanguage(Locale.CHINA)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onSpeakingDone?.invoke() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onSpeakingDone?.invoke() }
        })
        nativeTtsReady = true
        Log.d(TAG, "Native TTS initialized successfully")
    }

    fun speak(text: String) {
        if (nativeTtsReady && tts != null) {
            Log.d(TAG, "Speaking via native TTS: $text")
            tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_reply")
        } else {
            Log.d(TAG, "Speaking via online TTS fallback: $text")
            speakOnline(text)
        }
    }

    private fun speakOnline(text: String) {
        releaseMediaPlayer()
        try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            // Use Google Translate TTS as fallback
            val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=zh-CN&q=$encoded"

            mediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { mp -> mp.start() }
                setOnCompletionListener {
                    releaseMediaPlayer()
                    onSpeakingDone?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    releaseMediaPlayer()
                    onSpeakingDone?.invoke()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Online TTS failed", e)
            onSpeakingDone?.invoke()
        }
    }

    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun stop() {
        tts?.stop()
        releaseMediaPlayer()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        releaseMediaPlayer()
    }
}
