# 05. Sequence, State and Security

> 时序、状态机、幂等与安全边界设计

---

# 1. 文本模式时序图

```text
Android
  -> 唤醒词命中
  -> 开始录音
  -> VAD 结束
  -> ASR 出文本
  -> POST /v1/voice/command 到 Bridge
Bridge
  -> 验证 token
  -> request_id 去重
  -> 调 OpenClaw ingest
Gateway
  -> 处理文本命令
  -> 返回 reply_text
Bridge
  -> 统一包装
Android
  -> 展示 reply_text
  -> 可选 TTS 播报
```

---

# 2. 音频模式时序图

```text
Android
  -> 唤醒词命中
  -> 开始录音
  -> VAD 结束
  -> POST /v1/voice/audio-command
Bridge
  -> 验证设备
  -> ASR 转写
  -> 文本清洗
  -> 调 OpenClaw ingest
Gateway
  -> 返回 reply_text
Bridge
  -> 返回 recognized_text + reply_text
Android
  -> 播报
```

---

# 3. 安卓端状态机

```kotlin
enum class VoiceState {
    IDLE,
    ARMED,
    WAKE_TRIGGERED,
    RECORDING,
    TRANSCRIBING,
    DISPATCHING,
    SPEAKING,
    ERROR
}
```

## 状态说明

- `IDLE`: 未监听或暂停状态
- `ARMED`: 已进入监听待命状态
- `WAKE_TRIGGERED`: 唤醒词命中或手动触发
- `RECORDING`: 正在录音
- `TRANSCRIBING`: 正在语音转文字
- `DISPATCHING`: 正在发送请求到 Bridge
- `SPEAKING`: 正在播报回复
- `ERROR`: 出错状态

---

# 4. 幂等与去重设计

## 4.1 request_id 幂等

安卓端每次请求应生成唯一 `request_id`，例如 UUID。

用途：

- 防止网络重试导致重复执行
- 方便日志追踪
- 便于 Bridge 去重

Bridge 建议：

- 使用 `request_id` 做幂等键
- TTL 建议 5 分钟
- 如果发现重复请求，可返回 409 或复用上一次结果

## 4.2 文本级防抖

建议按设备做短时间重复文本抑制，例如：

- 3 秒内同设备同文本不重复提交

适合处理：

- 双击触发
- 误触发
- 失败重发

---

# 5. 鉴权设计

## 5.1 安卓 -> Bridge

最少要求：

- HTTPS
- Bearer Token
- `X-Device-Id`
- `X-Request-Id`

建议请求头：

```http
Authorization: Bearer <device_token>
X-Device-Id: android-phone-01
X-Request-Id: uuid-1234
```

## 5.2 Bridge -> Gateway

最少要求：

- 内网访问
- 独立 Bearer Token

建议不要和安卓 token 复用。

---

# 6. HMAC 与防重放建议

如果后续需要更强安全性，可加：

- `X-Timestamp`
- `X-Signature`

签名内容：

```text
device_id + request_id + timestamp + body_sha256
```

Bridge 校验：

- 时间窗是否在允许范围内（例如 5 分钟）
- HMAC 是否正确

这样可以防止：

- 请求伪造
- 抓包重放

---

# 7. 网络边界设计

## 7.1 安卓到 Bridge

- 必须 HTTPS
- 可公网访问，但需要严格鉴权

## 7.2 Bridge 到 Gateway

- 建议内网 HTTP 或 HTTPS
- 不暴露公网
- 最好只允许固定来源 Bridge 访问

推荐链路：

```text
Android -> HTTPS Bridge -> 内网 Gateway
```

---

# 8. 限流设计

按设备限流，例如：

- 每分钟最多 20 次请求
- 每小时最多 200 次请求

触发限流时返回：

- `429 Too Many Requests`

这样可以防止：

- 程序异常循环
- 误触发风暴
- 恶意刷接口

---

# 9. 日志与审计

## 9.1 安卓端日志

建议记录：

- 唤醒时间
- 录音时长
- ASR 耗时
- 发送结果
- 错误码
- reply_text 摘要

## 9.2 Bridge 日志

建议字段：

- request_id
- device_id
- recognized_text
- gateway_status
- total_latency_ms
- asr_latency_ms
- gateway_latency_ms
- result

注意：

- 默认不要长期保存原始音频
- 文本日志要考虑隐私风险

---

# 10. 安全建议汇总

必做：

- HTTPS
- 每设备独立 token
- Gateway 内部接口不暴露公网
- request_id 幂等
- Bridge 限流

建议做：

- HMAC 签名
- 时间窗校验
- IP 白名单
- 更细粒度设备权限模型
