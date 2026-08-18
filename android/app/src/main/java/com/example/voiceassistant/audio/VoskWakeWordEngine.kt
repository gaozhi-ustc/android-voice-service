package com.example.voiceassistant.audio

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Wake word detection using Vosk offline ASR with grammar constraint.
 * Downloads large model on first launch for high accuracy.
 * Listens for "你好管家".
 */
class VoskWakeWordEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoskWakeWordEngine"
        private const val MODEL_DIR = "vosk-model-cn-0.22"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip"
        private const val WAKE_PHRASE = "你好管家"
        private const val COOLDOWN_MS = 3000L

        // Grammar constrains Vosk to only recognize these words
        private const val GRAMMAR = "[\"你好 管家\", \"你好管家\", \"[unk]\"]"
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var listener: ((WakeEvent) -> Unit)? = null
    @Volatile
    private var running = false
    @Volatile
    private var modelReady = false
    private var lastTriggerTime = 0L

    var onModelProgress: ((String) -> Unit)? = null

    val frameConsumer: (ByteArray) -> Unit = { frame ->
        if (running && modelReady) {
            processFrame(frame)
        }
    }

    /**
     * Initialize Vosk model. Downloads large model on first launch.
     * Call from background thread.
     */
    fun initModel(): Boolean {
        return try {
            val modelDir = getOrDownloadModel()
            if (modelDir != null) {
                model = Model(modelDir.absolutePath)
                modelReady = true
                Log.d(TAG, "Vosk model loaded successfully")
                true
            } else {
                Log.e(TAG, "Failed to get model")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Vosk model", e)
            false
        }
    }

    fun start() {
        if (!modelReady) {
            Log.w(TAG, "Model not ready, cannot start")
            return
        }
        try {
            recognizer?.close()
            recognizer = Recognizer(model, AudioRouter.SAMPLE_RATE.toFloat(), GRAMMAR)
            running = true
            Log.d(TAG, "Vosk wake word engine started (grammar mode, wake='$WAKE_PHRASE')")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create recognizer", e)
        }
    }

    fun stop() {
        running = false
        recognizer?.close()
        recognizer = null
    }

    fun setListener(listener: (WakeEvent) -> Unit) {
        this.listener = listener
    }

    fun isModelReady(): Boolean = modelReady

    private fun processFrame(frame: ByteArray) {
        val rec = recognizer ?: return

        if (rec.acceptWaveForm(frame, frame.size)) {
            val result = rec.result
            Log.d(TAG, "Final: $result")
            checkForWakeWord(result)
        } else {
            checkForWakeWord(rec.partialResult)
        }
    }

    private fun checkForWakeWord(jsonResult: String) {
        try {
            val json = JSONObject(jsonResult)
            val text = json.optString("text", "").ifEmpty {
                json.optString("partial", "")
            }
            if (text.isBlank()) return

            // Check for wake phrase — match with or without spaces
            val normalized = text.replace(" ", "")
            if (normalized.contains("你好管家") || normalized.contains("管家")) {
                val now = System.currentTimeMillis()
                if (now - lastTriggerTime > COOLDOWN_MS) {
                    lastTriggerTime = now
                    Log.d(TAG, "Wake word detected: '$text'")
                    listener?.invoke(
                        WakeEvent(
                            keyword = WAKE_PHRASE,
                            confidence = 1.0f,
                            timestamp = now
                        )
                    )
                    recognizer?.close()
                    recognizer = Recognizer(model, AudioRouter.SAMPLE_RATE.toFloat(), GRAMMAR)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Vosk result", e)
        }
    }

    /**
     * Get model from local storage. If not present, try assets first, then download.
     */
    private fun getOrDownloadModel(): File? {
        val targetDir = File(context.filesDir, MODEL_DIR)

        // Already downloaded?
        if (targetDir.exists() && targetDir.isDirectory && (targetDir.list()?.size ?: 0) > 3) {
            Log.d(TAG, "Model already exists at ${targetDir.absolutePath}")
            return targetDir
        }

        // Check if zip exists (adb push to /data/local/tmp or /sdcard)
        val sdcardZip = arrayOf(
            File("/data/local/tmp/vosk-model-cn-0.22.zip"),
            File("/sdcard/vosk-model-cn-0.22.zip")
        ).firstOrNull { it.exists() && it.length() > 1_000_000 }
        if (sdcardZip != null) {
            Log.d(TAG, "Found model zip at ${sdcardZip.absolutePath}, extracting...")
            onModelProgress?.invoke("正在从本地解压模型...")
            try {
                targetDir.deleteRecursively()
                ZipInputStream(sdcardZip.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(context.filesDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                Log.d(TAG, "Model extracted from local zip")
                onModelProgress?.invoke("模型准备完成")
                return targetDir
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract from local zip", e)
            }
        }

        // Download large model as last resort
        Log.d(TAG, "Downloading large model from $MODEL_URL ...")
        onModelProgress?.invoke("正在下载语音模型...")
        return downloadAndExtractModel(targetDir)
    }

    private fun downloadAndExtractModel(targetDir: File): File? {
        val zipFile = File(context.cacheDir, "vosk-model-cn-0.22.zip")
        try {
            // Download
            if (!zipFile.exists() || zipFile.length() < 1_000_000) {
                Log.d(TAG, "Downloading model zip...")
                onModelProgress?.invoke("正在下载语音模型 (~1.3GB)...")
                URL(MODEL_URL).openStream().use { input ->
                    BufferedInputStream(input, 8192).use { bis ->
                        FileOutputStream(zipFile).use { output ->
                            val buffer = ByteArray(8192)
                            var totalRead = 0L
                            var lastLog = 0L
                            var read: Int
                            while (bis.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                totalRead += read
                                if (totalRead - lastLog > 50_000_000) {
                                    val mb = totalRead / 1_000_000
                                    Log.d(TAG, "Downloaded ${mb}MB...")
                                    onModelProgress?.invoke("已下载 ${mb}MB...")
                                    lastLog = totalRead
                                }
                            }
                        }
                    }
                }
                Log.d(TAG, "Download complete: ${zipFile.length() / 1_000_000}MB")
            }

            // Extract
            Log.d(TAG, "Extracting model...")
            onModelProgress?.invoke("正在解压模型...")
            targetDir.deleteRecursively()
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(context.filesDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            zis.copyTo(out)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Clean up zip
            zipFile.delete()
            Log.d(TAG, "Model extracted to ${targetDir.absolutePath}")
            onModelProgress?.invoke("模型准备完成")
            return targetDir
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download/extract model", e)
            onModelProgress?.invoke("模型下载失败: ${e.message}")
            zipFile.delete()
            return null
        }
    }

    fun shutdown() {
        stop()
        model?.close()
        model = null
        modelReady = false
    }

    fun simulateTrigger() {
        listener?.invoke(
            WakeEvent(
                keyword = WAKE_PHRASE,
                confidence = 1.0f,
                timestamp = System.currentTimeMillis()
            )
        )
    }
}
