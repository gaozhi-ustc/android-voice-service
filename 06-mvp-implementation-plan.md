# 06. MVP Implementation Plan

> 按实施顺序推进安卓端 + Bridge + OpenClaw Gateway 语音服务

---

# 1. MVP 目标

第一阶段不追求“像天猫精灵一样完整”，而是先打通闭环：

- 安卓能录音
- 能把音频或文字发到 Bridge
- Bridge 能调用 OpenClaw Gateway
- 能拿到回复
- 安卓能把回复播出来

---

# 2. 实施阶段划分

## Phase 1：最小闭环

交付内容：

- 安卓前台服务
- 手动触发录音
- `/v1/voice/audio-command` 接口
- Bridge 假 ASR 或真实 ASR
- Bridge -> Gateway 转发
- 安卓 TTS 播报

目标：

- 先证明端到端链路可用

## Phase 2：增强体验

交付内容：

- VAD 自动截断录音
- 错误处理和重试
- request_id 去重
- 重复文本防抖
- 日志完善

目标：

- 提升稳定性和可用性

## Phase 3：智能音箱化

交付内容：

- 唤醒词接入
- 提示音
- 连续对话短窗口
- 多设备管理
- 配置页面增强

目标：

- 逐步接近“持续在线语音助手”体验

---

# 3. 推荐开发顺序

## 3.1 先做 Bridge

原因：

- 协议先定下来，安卓和 Gateway 才好接
- 服务端更容易先 mock

工作项：

- 健康检查接口
- `/v1/voice/command`
- `/v1/voice/audio-command`
- 简单 token 校验
- 调 Gateway 假实现或 mock

## 3.2 再做安卓端录音 + HTTP

工作项：

- Foreground Service
- 录音
- 手动触发
- 调 Bridge
- TTS 播报

## 3.3 再做 Gateway ingest

工作项：

- `/internal/openclaw/ingest`
- 文本注入主会话
- 返回 reply_text

## 3.4 最后加体验增强

工作项：

- VAD
- 唤醒词
- 去重
- 更完善日志

---

# 4. 联调顺序

## 第一步：Bridge 自测

- 直接 curl `/v1/health`
- 直接 curl `/v1/voice/command`
- mock Gateway 返回固定 reply_text

## 第二步：安卓接 Bridge

- 手动按钮录音
- 上传请求
- 手机收到固定 reply_text 并播报

## 第三步：Bridge 接 Gateway

- 用真实 Gateway 替换 mock
- 验证 reply_text 返回

## 第四步：补音频链路

- 安卓传音频
- Bridge 做转写
- 再发 Gateway

---

# 5. 风险点

## 5.1 安卓后台保活

风险：

- 系统杀后台
- 厂商电池优化干扰

建议：

- 前台服务
- 引导加入白名单
- 提供服务状态页

## 5.2 语音结束判定不稳定

风险：

- 录音过短
- 录音过长
- 静音误判

建议：

- MVP 先手动停止
- 第二阶段再加 VAD

## 5.3 网络不稳定

风险：

- 请求超时
- 重复重试

建议：

- request_id 幂等
- 失败后最多 1~2 次重试

## 5.4 Gateway 响应太长

风险：

- 不适合 TTS
- 播报体验差

建议：

- reply_text 设计成口语化短文本
- 必要时区分 reply_text 和 display_text

---

# 6. 测试重点

## 安卓端

- 录音权限
- 前台服务稳定性
- 录音质量
- TTS 是否正常
- 弱网下的表现

## Bridge

- token 校验
- 参数校验
- request_id 去重
- 异常返回
- 超时处理

## Gateway

- 会话注入是否成功
- reply_text 是否返回
- 错误结构是否统一

---

# 7. 上线前检查清单

## 安卓端

- [ ] 前台服务可持续运行
- [ ] 录音权限申请正常
- [ ] TTS 正常
- [ ] Bridge 地址配置正确
- [ ] token 已配置

## Bridge

- [ ] HTTPS 已启用
- [ ] token 校验开启
- [ ] 日志开启
- [ ] 限流开启
- [ ] Gateway 地址配置正确

## Gateway

- [ ] ingest 接口可用
- [ ] 仅内网开放
- [ ] reply_text 返回正常

---

# 8. 推荐结论

MVP 最稳的路线是：

1. Bridge 先行
2. 安卓手动触发打通闭环
3. Gateway 接上真实会话
4. 再逐步补 VAD、唤醒词和多设备能力
