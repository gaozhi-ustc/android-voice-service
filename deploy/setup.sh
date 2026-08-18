#!/bin/bash
# Voice Bridge 一键部署脚本（Linux 服务器）
# 用法: sudo bash setup.sh
# 作用: 安装代码到 /opt/voice-bridge, 建 venv, 装依赖, 生成 .env,
#       注册 systemd 服务 voice-bridge 并启动。
set -e

INSTALL_DIR=/opt/voice-bridge
SRC_DIR="$(cd "$(dirname "$0")/.." && pwd)"   # 仓库根目录
PORT=8000

if [ "$(id -u)" != "0" ]; then
    echo "请用 sudo 运行: sudo bash $0"
    exit 1
fi
command -v python3 >/dev/null || { echo "错误: 需要 python3 (>=3.9)"; exit 1; }

echo "== 1. 部署代码到 $INSTALL_DIR =="
rm -rf "$INSTALL_DIR"
mkdir -p "$INSTALL_DIR"
cp -r "$SRC_DIR/bridge" "$INSTALL_DIR/bridge"
cp -r "$SRC_DIR/tools"  "$INSTALL_DIR/tools"

echo "== 2. 创建 venv 并安装依赖 =="
python3 -m venv "$INSTALL_DIR/.venv"
"$INSTALL_DIR/.venv/bin/pip" install -q --upgrade pip
"$INSTALL_DIR/.venv/bin/pip" install -q -r "$INSTALL_DIR/bridge/requirements.txt"
echo "   依赖安装完成（首次会自动下载 Whisper 模型缓存目录: ~/.cache/huggingface）"

echo "== 3. 生成 .env =="
if [ ! -f "$INSTALL_DIR/bridge/.env" ]; then
    TOKEN=$(python3 -c "import secrets; print(secrets.token_hex(16))")
    sed "s/^bridge_token=.*/bridge_token=$TOKEN/" \
        "$SRC_DIR/bridge/.env.example" > "$INSTALL_DIR/bridge/.env"
    echo "   已生成 .env，Token: $TOKEN"
    echo "   !! 把这个 Token 填进手机 App 的 Bridge Token 设置里 !!"
else
    echo "   使用已有 .env"
fi

echo "== 4. 注册 systemd 服务 =="
cp "$SRC_DIR/deploy/voice-bridge.service" /etc/systemd/system/voice-bridge.service
systemctl daemon-reload
systemctl enable voice-bridge
systemctl restart voice-bridge
sleep 3
systemctl --no-pager status voice-bridge | head -5

IP=$(hostname -I 2>/dev/null | awk '{print $1}')
echo ""
echo "============================================================"
echo " 部署完成！"
echo "   服务地址: http://$IP:$PORT"
echo "   健康检查: curl http://$IP:$PORT/v1/health"
echo "   日志:     journalctl -u voice-bridge -f"
echo "   停止:     systemctl stop voice-bridge"
echo ""
echo " 注意: 任务队列(/tmp/voice-bridge-tasks)由 agent 会话消费。"
echo " 本服务器若没有 agent 消费者，语音会 ASR 成功但等不到答案。"
echo "============================================================"
