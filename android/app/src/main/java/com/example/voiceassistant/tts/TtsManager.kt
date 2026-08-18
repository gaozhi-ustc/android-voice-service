package com.example.voiceassistant.tts

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "adhoc") {
                    adHocCallbacks.remove(utteranceId)?.invoke()
                    return
                }
                speaking = false; onSpeakingDone?.invoke()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == "adhoc") {
                    adHocCallbacks.remove(utteranceId)?.invoke()
                    return
                }
                speaking = false; onSpeakingDone?.invoke()
            }
        })
        engine.setSpeechRate(1.5f)
        nativeTtsReady = true
        Log.d(TAG, "Native TTS initialized successfully")
    }

    fun speak(text: String) {
        speaking = true
        val cleaned = stripMarkdown(text)
        if (nativeTtsReady && tts != null) {
            Log.d(TAG, "Speaking via native TTS: $cleaned")
            tts!!.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, "voice_reply")
        } else {
            Log.d(TAG, "Speaking via online TTS fallback: $cleaned")
            speakOnline(cleaned)
        }
    }

    private fun stripMarkdown(text: String): String {
        return text
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")  // **bold**
            .replace(Regex("\\*(.+?)\\*"), "$1")          // *italic*
            .replace(Regex("__(.+?)__"), "$1")            // __bold__
            .replace(Regex("_(.+?)_"), "$1")              // _italic_
            .replace(Regex("~~(.+?)~~"), "$1")            // ~~strikethrough~~
            .replace(Regex("`(.+?)`"), "$1")              // `code`
            .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "") // headings
            .replace(Regex("^[\\-*+]\\s+", RegexOption.MULTILINE), "") // bullet lists
            .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "") // numbered lists
            .replace(Regex("\\[(.+?)]\\(.+?\\)"), "$1")  // [link](url)
            .trim()
    }

    private val pendingChunks = mutableListOf<String>()

    private fun speakOnline(text: String) {
        releaseMediaPlayer()
        val chunks = splitText(text)
        if (chunks.isEmpty()) {
            onSpeakingDone?.invoke()
            return
        }
        synchronized(pendingChunks) {
            pendingChunks.clear()
            pendingChunks.addAll(chunks)
        }
        playNextChunk()
    }

    private fun splitText(text: String, maxLen: Int = 150): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val result = mutableListOf<String>()
        val delimiters = charArrayOf('。', '！', '？', '；', '\n', '，', ',', '.', '!', '?')
        var remaining = text
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxLen) {
                result.add(remaining)
                break
            }
            var cutAt = -1
            for (i in maxLen downTo maxLen / 2) {
                if (remaining[i] in delimiters) {
                    cutAt = i + 1
                    break
                }
            }
            if (cutAt <= 0) cutAt = maxLen
            result.add(remaining.substring(0, cutAt).trim())
            remaining = remaining.substring(cutAt).trim()
        }
        return result.filter { it.isNotBlank() }
    }

    private fun playNextChunk() {
        val chunk: String
        synchronized(pendingChunks) {
            if (pendingChunks.isEmpty()) {
                speaking = false
                onSpeakingDone?.invoke()
                return
            }
            chunk = pendingChunks.removeAt(0)
        }

        try {
            val encoded = URLEncoder.encode(chunk, "UTF-8")
            val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=zh-CN&q=$encoded"

            mediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { mp ->
                    mp.playbackParams = PlaybackParams().setSpeed(1.5f)
                    mp.start()
                }
                setOnCompletionListener {
                    releaseMediaPlayer()
                    playNextChunk()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    releaseMediaPlayer()
                    playNextChunk()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Online TTS chunk failed", e)
            playNextChunk()
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

    @Volatile
    private var speaking = false

    fun isSpeaking(): Boolean = speaking

    fun stop() {
        speaking = false
        tts?.stop()
        synchronized(pendingChunks) {
            pendingChunks.clear()
        }
        releaseMediaPlayer()
    }

    // --- Prompt cache for instant "请讲" playback ---

    private val promptCacheDir by lazy { File(appContext.cacheDir, "tts_prompts") }
    private var promptPlayer: MediaPlayer? = null

    /**
     * Pre-download common prompts to local cache for instant playback.
     * Call from background thread.
     */
    fun preloadPrompts() {
        promptCacheDir.mkdirs()
        cachePrompt("请讲")
    }

    private val httpClient = OkHttpClient()

    /**
     * Ad-hoc speak that does NOT touch the session's onSpeakingDone callback.
     * Used for late reply delivery (adb broadcast) so the voice session
     * state machine is not disturbed.
     */
    private val adHocCallbacks = HashMap<String, () -> Unit>()

    fun speakAdHoc(text: String, onDone: (() -> Unit)? = null) {
        val cleaned = stripMarkdown(text)
        if (cleaned.isBlank()) {
            onDone?.invoke()
            return
        }
        if (nativeTtsReady && tts != null) {
            Log.d(TAG, "Ad-hoc TTS: $cleaned")
            adHocCallbacks["adhoc"] = onDone ?: {}
            tts!!.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, "adhoc")
        } else {
            // Rare fallback: borrow the online path (native TTS unavailable)
            Log.d(TAG, "Ad-hoc TTS via online fallback: $cleaned")
            onSpeakingDone = onDone
            speakOnline(cleaned)
        }
    }

    private fun cachePrompt(text: String): File? {
        val file = File(promptCacheDir, "${text.hashCode()}.mp3")
        if (file.exists() && file.length() > 0) {
            Log.d(TAG, "Prompt '$text' already cached")
            return file
        }
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=zh-CN&q=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Cache download failed: ${response.code}")
                    return null
                }
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            Log.d(TAG, "Cached prompt '$text' -> ${file.absolutePath} (${file.length()} bytes)")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache prompt '$text'", e)
            null
        }
    }

    /**
     * Play a cached prompt instantly from local file.
     * Falls back to online TTS if cache miss.
     */
    fun speakPrompt(text: String, onDone: (() -> Unit)? = null) {
        val file = File(promptCacheDir, "${text.hashCode()}.mp3")
        if (file.exists() && file.length() > 0) {
            Log.d(TAG, "Playing cached prompt: $text")
            try {
                releasePromptPlayer()
                promptPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnPreparedListener { mp ->
                        mp.playbackParams = PlaybackParams().setSpeed(1.5f)
                        mp.start()
                    }
                    setOnCompletionListener {
                        releasePromptPlayer()
                        onDone?.invoke()
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "Prompt player error: what=$what extra=$extra")
                        releasePromptPlayer()
                        onDone?.invoke()
                        true
                    }
                    prepare()  // Sync prepare — local file, instant
                    playbackParams = PlaybackParams().setSpeed(1.5f)
                    start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play cached prompt", e)
                releasePromptPlayer()
                onDone?.invoke()
            }
        } else {
            Log.d(TAG, "Prompt not cached, using online TTS: $text")
            onSpeakingDone = onDone
            speak(text)
        }
    }

    private fun releasePromptPlayer() {
        try {
            promptPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        promptPlayer = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        releaseMediaPlayer()
        releasePromptPlayer()
    }
}
