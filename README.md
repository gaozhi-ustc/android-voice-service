# Android Voice Service

Android 端 + Bridge + OpenClaw Gateway 持续在线语音服务。类似"天猫精灵"的语音助手方案，安卓手机作为常驻监听终端。

## Architecture

```
┌──────────────────────┐
│      Android App     │
│  - Foreground Service│
│  - Audio Recording   │
│  - VAD (Voice End)   │
│  - TTS Playback      │
└──────────┬───────────┘
           │ HTTP + Bearer Token
           ▼
┌──────────────────────┐
│     Bridge Service   │
│  - Device Auth       │
│  - ASR (Speech→Text) │
│  - Request Dedup     │
│  - Rate Limiting     │
│  - Forward to Gateway│
└──────────┬───────────┘
           │ Internal HTTP
           ▼
┌──────────────────────┐
│   OpenClaw Gateway   │
│  - Execute Agent     │
│  - Return Reply Text │
└──────────────────────┘
```

## Modules

### Android App (`android/`)

Kotlin Android app with:

- **VoiceForegroundService** — 常驻前台服务，开机自启
- **RecorderManager** — 16kHz PCM mono 录音
- **VadController** — 基于能量的 VAD（8s 最大录音，1s 静音截断）
- **GatewayClient** — OkHttp 客户端，发送音频到 Bridge
- **TtsManager** — TTS 播报（本地引擎 + 在线 TTS 自动降级）
- **VoiceSessionController** — 状态机编排整个语音链路
- **Settings UI** — 配置 Bridge 地址、Token、设备 ID

### Bridge Server (`bridge/`)

FastAPI 服务，统一接入层：

- `GET /v1/health` — 健康检查
- `POST /v1/voice/command` — 文本指令
- `POST /v1/voice/audio-command` — 音频上传 + ASR + 执行
- `POST /v1/asr/transcribe` — 纯 ASR 转写

## Quick Start

### 1. Start Bridge

```bash
cd bridge
pip install -r requirements.txt
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

### 2. Build & Install Android App

```bash
cd android
export JAVA_HOME="/path/to/jdk17"
export ANDROID_HOME="/path/to/android-sdk"
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. Configure App

Open app → Settings:

- **Bridge URL**: `http://<your-pc-ip>:8000`
- **Bridge Token**: `change-me`
- **Device ID**: `android-phone-01`

### 4. Test

Press "START RECORDING", speak, wait for auto-stop. The app will:

1. Record audio with VAD auto-cutoff
2. Send WAV to Bridge
3. Bridge runs ASR → forwards to Gateway
4. Receive reply text
5. TTS playback

## Design Docs

- [01 - Overview and Architecture](01-overview-and-architecture.md)
- [02 - Android App Design](02-android-app-design.md)
- [03 - Bridge API Design](03-bridge-api-design.md)
- [04 - OpenClaw Gateway Ingest Design](04-openclaw-gateway-ingest-design.md)
- [05 - Sequence, State and Security](05-sequence-state-and-security.md)
- [06 - MVP Implementation Plan](06-mvp-implementation-plan.md)

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Android | Kotlin, OkHttp, Android TTS, AudioRecord |
| Bridge | Python, FastAPI, httpx |
| Build | Gradle 8.5, AGP 8.2.2, Kotlin 1.9.22 |
| Target | Android 11+ (API 30+) |

## License

Private project.
