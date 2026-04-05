# 04. OpenClaw Gateway Ingest Design

> OpenClaw Gateway 侧的语音文本接入设计

---

# 1. 设计目标

Gateway 侧只做一件事：

- 接收 Bridge 发来的文本命令
- 当作普通文本消息处理
- 返回结果文本

Gateway 不需要理解安卓端录音细节、唤醒词细节、音频编码细节。

---

# 2. 推荐接口

## 2.1 Endpoint

`POST /internal/openclaw/ingest`

该接口应：

- 只对 Bridge 开放
- 不直接暴露给公网
- 只接受结构化文本请求

## 2.2 Request Body

```json
{
  "source": "voice-bridge",
  "channel": "voice",
  "user_id": "gaozhi",
  "session_target": "main",
  "text": "帮我看看明天的日程",
  "context": {
    "device_id": "android-phone-01",
    "trigger_type": "wakeword",
    "lang": "zh-CN",
    "request_id": "4f4ddf3a-0f30-4d1a-9a68-123456789abc"
  }
}
```

### 字段说明

- `source`: 请求来源，固定为 `voice-bridge`
- `channel`: 来源通道，建议固定 `voice`
- `user_id`: 逻辑用户标识
- `session_target`: 要注入的 OpenClaw 会话，默认 `main`
- `text`: 识别后的文本命令
- `context`: 附加上下文信息

## 2.3 Response Body

```json
{
  "ok": true,
  "reply_text": "明天上午十点有会议，下午两点要整理周报。",
  "session_key": "main",
  "message_id": "oc-001"
}
```

### 字段说明

- `ok`: 是否成功
- `reply_text`: 返回给安卓端展示或播报的文本
- `session_key`: 实际处理的会话
- `message_id`: Gateway 生成的消息 ID

---

# 3. Gateway 内部处理流程

```text
Bridge -> /internal/openclaw/ingest
       -> 校验来源
       -> 校验 token
       -> 解析 session_target
       -> 注入 OpenClaw 会话
       -> 等待 Agent 响应
       -> 提取 reply_text
       -> 返回 Bridge
```

建议 Gateway 增加一个专门的 handler：

- `voice_ingest_handler`

职责：

- 校验 bridge 身份
- 组装标准内部消息
- 注入目标会话
- 获取结果
- 返回标准响应

---

# 4. 安全边界

## 4.1 不直接暴露公网

该接口不应该让安卓直接访问。建议仅允许：

- `127.0.0.1`
- 内网网段
- Docker 内部网络
- 反向代理内网 upstream

## 4.2 鉴权建议

最少要求：

- Bearer Token

更好的做法：

- Bridge 与 Gateway 之间使用独立 token
- 结合 IP 白名单
- 可选 HMAC

## 4.3 输入校验

Gateway 至少校验：

- `source` 是否等于 `voice-bridge`
- `text` 是否为空
- `session_target` 是否合法
- `request_id` 是否存在

---

# 5. 会话注入设计

## 5.1 默认方案

所有语音指令注入主会话：

```json
{
  "session_target": "main"
}
```

适合：

- 单用户助手
- 与当前聊天主上下文共享记忆

## 5.2 可扩展方案

支持不同设备或不同业务路由到不同 session：

例如：

- `main`
- `voice-home`
- `voice-office`
- `calendar-agent`

Bridge 可根据设备或路由规则指定 `session_target`。

---

# 6. reply_text 设计建议

Gateway 返回的 `reply_text` 应尽量适合语音播报。

建议：

- 简洁
- 自然口语化
- 避免大段 markdown
- 避免过长列表
- 避免复杂符号和链接

例如：

不推荐：

```text
## 今日待办
1. 完成报表
2. 回复邮件
3. 准备会议资料
```

推荐：

```text
今天有三项待办：完成报表、回复邮件、准备会议资料。
```

如果需要，后续可以支持：

- `reply_text`：用于播报
- `display_text`：用于界面显示

---

# 7. 错误响应建议

统一结构：

```json
{
  "ok": false,
  "error_code": "INVALID_SESSION",
  "message": "session_target is invalid"
}
```

建议错误码：

- `INVALID_TOKEN`
- `INVALID_SOURCE`
- `INVALID_SESSION`
- `EMPTY_TEXT`
- `AGENT_TIMEOUT`
- `INTERNAL_ERROR`

---

# 8. 可扩展字段建议

后续可在 `context` 中增加：

```json
{
  "device_id": "android-phone-01",
  "trigger_type": "wakeword",
  "lang": "zh-CN",
  "request_id": "req-001",
  "audio_duration_ms": 4200,
  "asr_latency_ms": 1100,
  "client_version": "1.0.0",
  "network_type": "wifi"
}
```

这些字段有利于：

- 日志分析
- 性能统计
- 调试排障

---

# 9. 推荐结论

Gateway 侧设计要尽量保持简单：

- 只接受文本
- 只做会话注入和结果返回
- 不处理音频细节
- 不暴露公网
- 通过 Bridge 承接所有接入治理逻辑
