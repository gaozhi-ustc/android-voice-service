# Voice Assistant ProGuard Rules

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Keep data models
-keep class com.example.voiceassistant.network.** { *; }
-keep class com.example.voiceassistant.data.models.** { *; }
