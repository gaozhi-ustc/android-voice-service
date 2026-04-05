# 01. Overview and Architecture

> 安卓端 + Bridge + OpenClaw Gateway 持续在线语音服务总览

---

# 1. 项目目标

目标是实现一个类似“天猫精灵”的持续在线语音服务：

- 安卓手机作为常驻监听终端
- 支持唤醒词或手动触发
- 采集语音并判断说话结束
- 将语音转成文字
- 通过 HTTP 将文本发送到 Bridge
- 由 Bridge 转发给 OpenClaw Gateway
- 接收 Gateway 返回结果
- 可选在安卓端用 TTS 播报结果

---

# 2. 总体架构

```text
┌──────────────────────┐
│      Android App     │
│  - 前台常驻服务       │
│  - 唤醒词/手动触发    │
│  - 录音/VAD           │
│  - ASR(本地或远程)    │
│  - HTTP 客户端        │
│  - TTS 播报           │
└──────────┬───────────┘
           │ HTTPS + Token
           ▼
┌──────────────────────┐
│     Bridge Service   │
│  - 设备鉴权           │
│  - 请求去重           │
│  - 指令预处理         │
│  - 日志/限流          │
│  - 调 OpenClaw        │
│  - 统一返回           │
└──────────┬───────────┘
           │ 内网 HTTP / HTTPS
           ▼
┌──────────────────────┐
│   OpenClaw Gateway   │
│  - 接收文本指令       │
│  - 执行 Agent         │
│  - 返回结果文本       │
└──────────────────────┘
```

---

# 3. 模块职责划分

## 3.1 安卓端职责

- 常驻监听
- 唤醒/手动触发
- 录音
- 语音结束判定
- ASR
- 文本过滤
- 发 HTTP 请求
- TTS 播报回复

## 3.2 Bridge 职责

- 验证设备身份
- 去重与幂等
- 限流
- 指令预处理
- 调用 OpenClaw Gateway
- 统一返回协议
- 审计日志与可观测性

## 3.3 OpenClaw Gateway 职责

- 接收文本命令
- 当作普通文本消息处理
- 返回结果文本

---

# 4. 推荐设计原则

## 4.1 分层清晰

- 安卓端负责采集、转写、上报、播报
- Bridge 负责接入、安全、治理、转发
- Gateway 负责 Agent 处理

## 4.2 安卓端不要直接打 Gateway 内部复杂接口

建议路径：

```text
Android -> Bridge -> OpenClaw Gateway
```

而不是：

```text
Android -> OpenClaw Gateway
```

原因：

- 更安全
- 更容易演进协议
- 更利于多设备接入
- 更方便做限流、去重、日志

## 4.3 MVP 先求闭环，再求体验

先完成：
- 录音
- 转文字
- 上报
- 获取回复
- TTS 播报

后续再补：
- 唤醒词
- VAD
- 连续对话
- 多设备管理

---

# 5. 推荐技术选型

## 5.1 安卓端

- Kotlin 原生 Android
- Foreground Service
- AudioRecord
- OkHttp
- Android TTS
- 后续可接 Porcupine Android SDK

## 5.2 Bridge

- FastAPI
- Uvicorn
- httpx
- Redis（正式版做去重/限流）
- SQLite/Postgres（可选）
- faster-whisper（如果服务端做 ASR）

## 5.3 OpenClaw Gateway

- 提供内部 ingest HTTP 接口
- 文本注入主会话或指定 session
- 返回 reply_text

---

# 6. MVP 路线图

## Phase 1：最小闭环

- 安卓前台服务
- 手动按钮触发录音
- 上传音频到 Bridge
- Bridge 调 ASR
- Bridge 调 Gateway
- 返回文本
- 安卓 TTS 播报

## Phase 2：增强体验

- 增加唤醒词
- 增加 VAD
- 增加重复请求抑制
- 增加错误语音提示

## Phase 3：产品化

- 多设备管理
- 后台管理页
- 设备在线状态
- 命令历史
- 本地 ASR 或混合 ASR
- 连续对话窗口

---

# 7. 文档拆分导航

后续详细设计见：

- `02-android-app-design.md`
- `03-bridge-api-design.md`
- `04-openclaw-gateway-ingest-design.md`
- `05-sequence-state-and-security.md`
- `06-mvp-implementation-plan.md`
