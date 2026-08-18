# Voice Bridge 部署到其他服务器

## 打包

```bash
tar -czf voice-bridge-deploy.tar.gz bridge tools deploy
```

把 `voice-bridge-deploy.tar.gz` 传到目标服务器：

```bash
scp voice-bridge-deploy.tar.gz user@server:~/
ssh user@server
cd ~ && tar -xzf voice-bridge-deploy.tar.gz
sudo bash deploy/setup.sh
```

## setup.sh 做了什么

1. 代码装到 `/opt/voice-bridge`
2. 建独立 venv + 装依赖（fastapi / uvicorn / faster-whisper ...）
3. 生成 `.env`（随机 Token，**打印出来，要填进手机 App**）
4. 注册 systemd 服务 `voice-bridge`（开机自启 + 崩溃 5s 自动拉起）
5. 启动并打印服务地址

## 手机端配置

App 设置里填：
- Bridge 地址：`http://<服务器IP>:8000`
- Token：setup.sh 打印的那个
- 手机与服务器需同网段可达；服务器防火墙放行 8000/TCP

## 关于"agent 会话"（重要）

Bridge 收到语音后做 ASR，然后把任务写进文件队列 `/tmp/voice-bridge-tasks/`，
等待一个 **agent 消费者**领取并写回答案。当前这套系统里，消费者就是运行在
Mac 上的编码 agent 会话（`bridge/tools/wait_task.py` 轮询队列）。

- **本机（Mac）**：消费者常驻，语音→答案完整闭环。
- **其他服务器**：Bridge 的 ASR / 鉴权 / 轮询补播都正常工作，但队列没人消费，
  语音会播"正在查询，请稍后"后没有后续。
  若想让该服务器也完整可用，需要在它上面跑一个任务消费者，例如：
  ```bash
  python3 tools/wait_task.py 3600   # 领取任务，自己写 replies/<id>.json
  ```
  或者把这里的 agent 逻辑换成调用任意 LLM / 网关。

## 常用运维

```bash
journalctl -u voice-bridge -f        # 看日志
systemctl restart voice-bridge       # 重启（改 .env 后）
/opt/voice-bridge/.venv/bin/python -c \
  "from faster_whisper import WhisperModel; WhisperModel('medium', device='cpu', compute_type='int8')"
                                      # 预热模型缓存
```

## 实战案例：192.168.0.64（Ubuntu 22.04，2026-08-18）

首次部署踩到的坑，全部已解决：

| 问题 | 原因 | 解决 |
|------|------|------|
| 端口 8000 被占 | 服务器上跑着 vllm 推理服务 | Bridge 改用 8001 |
| `python3 -m venv` 失败 | Ubuntu 缺 python3-venv 包（要 sudo） | 改 `pip install --user` |
| 启动即崩 `extra_forbidden` | pydantic-settings 默认拒绝 .env 里的未知变量 | 代码已修（extra=ignore + 正式声明字段） |
| ASR 卡死 2 分钟 | 服务器访问不了 HuggingFace，hub 校验 revision 失败 | `HF_HUB_OFFLINE=1` + 提前 rsync 模型缓存（1.5G，局域网秒传） |
| ssh 里 pkill 自杀 | pkill -f 的模式匹配到 ssh 自己的 bash -c 命令行 | pkill 和启动命令分开两条 ssh 执行 |
| 服务器时钟慢 40 分钟 | 未同步 NTP | 建议 `sudo timedatectl set-ntp true`（不影响功能，只影响日志时间） |

部署产物：`deploy/voice-bridge-0.64.service`（该服务器专用 systemd 单元，
`User=gaozhi` + 8001 + HF_HUB_OFFLINE，装法见上文的 sudo 命令，端口按此单元）。

手机端注意：手机在 192.168.1.x 网段，服务器在 192.168.0.x，
若路由器不跨网段路由，手机连不上服务器，需要在同一网段下使用。
