# Android Voice Service - Project Notes

## Project Overview

Android 端 + Bridge + OpenClaw Gateway 持续在线语音服务。安卓手机作为常驻监听终端，通过 Bridge 转发到 OpenClaw Gateway 执行指令并返回结果。

## Project Structure

```
android-voice-service/
├── android/          # Android app (Kotlin)
├── bridge/           # Bridge server (FastAPI/Python)
├── 01~06-*.md        # Design docs
```

## Build & Deploy

### Android App

```bash
cd android
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
./gradlew assembleDebug
adb -s 192.168.1.34:45963 install -r app/build/outputs/apk/debug/app-debug.apk
```

- Gradle 8.5, AGP 8.2.2, Kotlin 1.9.22
- minSdk 26, targetSdk 34
- Device: Xiaomi M2104K10AC, Android 11 (API 30)
- ADB WiFi debug port: 45963 (may change on reconnect)

### Bridge Server

```bash
cd bridge
python3 -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

- Python 3.9 (Xcode bundled), need `Optional[str]` instead of `str | None`
- Local IP for phone testing: 192.168.1.32

## Key Technical Decisions

### TTS

Native Android `TextToSpeech` API fails on this Xiaomi device (XiaoAi Speech Engine registers TTS_SERVICE but `onInit` always returns -1). Solution: automatic fallback to online TTS via `MediaPlayer` + Google Translate TTS endpoint.

### ASR

MVP flow sends audio (PCM -> WAV) directly to Bridge's `/v1/voice/audio-command` endpoint for remote ASR, rather than doing local ASR on Android. Bridge's ASR service is currently a stub returning mock text — needs real ASR integration (e.g. faster-whisper).

### Network Security

`network_security_config.xml` allows cleartext HTTP to local IPs (192.168.1.32, 10.0.0.0, localhost) for development. Production should use HTTPS only.

### Gateway Mock

`bridge/app/services/gateway_service.py` has a try/except fallback that returns mock responses when OpenClaw Gateway is unavailable. Remove this for production.

## 2026-04-05 Work Log

### Done

1. **Created full Android app** based on `02-android-app-design.md`:
   - Foreground service with notification and boot auto-start
   - PCM 16kHz mono audio recording with VAD (8s max, 1s silence threshold)
   - Voice session state machine: IDLE -> RECORDING -> DISPATCHING -> SPEAKING -> IDLE
   - OkHttp client for Bridge API (`/v1/voice/command` and `/v1/voice/audio-command`)
   - Settings UI (Bridge URL, Token, Device ID persisted in SharedPreferences)
   - Command filter with 3s dedup, retry policy with exponential backoff

2. **Setup build environment on Mac**:
   - Installed android-commandlinetools, openjdk@17, gradle via Homebrew
   - Configured SDK (platforms;android-34, build-tools;34.0.0)

3. **On-device testing via ADB WiFi**:
   - Paired and connected to Xiaomi phone via wireless debugging
   - Built, installed, and verified all modules on real device

4. **Fixed TTS** — online TTS fallback when native engine unavailable

5. **Fixed ASR** — remote ASR via Bridge `/v1/voice/audio-command` with PCM-to-WAV encoding

6. **Fixed Bridge** — Python 3.9 compatibility, added Gateway mock fallback

7. **End-to-end test passed**: Record -> VAD auto-stop -> Upload WAV to Bridge -> ASR -> Gateway -> TTS playback

### TODO

- Integrate real ASR engine in Bridge (faster-whisper or external API)
- Install a proper TTS engine on phone (or keep online fallback)
- Connect Bridge to real OpenClaw Gateway
- HTTPS for production
- Wake word integration (Porcupine)
