package com.example.voiceassistant.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.example.voiceassistant.BuildConfig

class AppPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("voice_assistant_prefs", Context.MODE_PRIVATE)

    var bridgeBaseUrl: String
        get() = prefs.getString(KEY_BRIDGE_URL, BuildConfig.BRIDGE_BASE_URL) ?: BuildConfig.BRIDGE_BASE_URL
        set(value) = prefs.edit().putString(KEY_BRIDGE_URL, value).apply()

    var bridgeToken: String
        get() = prefs.getString(KEY_BRIDGE_TOKEN, BuildConfig.BRIDGE_TOKEN) ?: BuildConfig.BRIDGE_TOKEN
        set(value) = prefs.edit().putString(KEY_BRIDGE_TOKEN, value).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, BuildConfig.DEVICE_ID) ?: BuildConfig.DEVICE_ID
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    companion object {
        private const val KEY_BRIDGE_URL = "bridge_base_url"
        private const val KEY_BRIDGE_TOKEN = "bridge_token"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
