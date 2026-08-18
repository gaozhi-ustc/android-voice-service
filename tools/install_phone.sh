#!/bin/bash
# Voice Assistant 新手机一键安装脚本
# 用法: ./tools/install_phone.sh [adb_serial]
# 自动化: 安装APK -> 推Vosk模型 -> 激活TTS(小米) -> 启动并验证
# MIUI 手点项(权限"始终允许"/自启动/电池无限制)会在最后打印清单
set -e
cd "$(dirname "$0")/.."

SERIAL="${1:-}"
if [ -n "$SERIAL" ]; then ADB="adb -s $SERIAL"; else ADB="adb"; fi

APK="android/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.example.voiceassistant"
MODEL_ZIP="${VOSK_MODEL_ZIP:-/tmp/vosk-model-cn-0.22.zip}"

echo "== 0. 检查 =="
$ADB get-state >/dev/null 2>&1 || { echo "错误: 没有可用设备，先 adb 连接"; exit 1; }
[ -f "$APK" ] || { echo "错误: 找不到 $APK，先构建 (cd android && ./gradlew assembleDebug)"; exit 1; }

echo "== 1. 安装 APK =="
# MIUI 会弹"继续安装"框，先后台安装再自动点击(非小米设备点击无害)
$ADB install -r "$APK" &
INSTALL_PID=$!
sleep 3.5
$ADB shell input tap 300 2020 2>/dev/null || true
wait $INSTALL_PID

echo "== 2. 推 Vosk 中文模型 =="
if [ -f "$MODEL_ZIP" ]; then
    WORK=$(mktemp -d)
    unzip -qo "$MODEL_ZIP" -d "$WORK"
    cd "$WORK"
    tar -cf "$WORK/model.tar" "$(ls -d vosk-model-* | head -1)"
    cd - >/dev/null
    $ADB push "$WORK/model.tar" /data/local/tmp/model.tar
    $ADB shell "run-as $PKG sh -c 'cd files && tar -xf /data/local/tmp/model.tar'"
    $ADB shell rm /data/local/tmp/model.tar
    rm -rf "$WORK"
    echo "   模型已推送"
else
    echo "   警告: 找不到 $MODEL_ZIP (可用 VOSK_MODEL_ZIP=... 指定)，跳过。唤醒词不可用！"
fi

echo "== 3. 激活 TTS 引擎 (仅小米生效, 其他品牌忽略) =="
$ADB shell settings put secure tts_default_speaker \
    com.xiaomi.mibrain.speech/com.xiaomi.mibrain.speech.tts.TtsService 2>/dev/null || true
echo "   tts_default_speaker = $($ADB shell settings get secure tts_default_speaker)"

echo "== 4. 启动应用 =="
$ADB shell am force-stop "$PKG"
$ADB shell am start -n "$PKG/.ui.MainActivity" >/dev/null
echo "   等待 25s 加载模型..."
sleep 25

echo "== 5. 验证 =="
LOG=$($ADB logcat -d 2>/dev/null | tail -300)
ok=1
echo "$LOG" | grep -q "AudioRecord started successfully" && echo "   [OK] 录音" || { echo "   [FAIL] 录音未启动 - 检查麦克风权限(见下方清单)"; ok=0; }
echo "$LOG" | grep -q "Vosk model loaded successfully" && echo "   [OK] 唤醒词模型" || { echo "   [FAIL] 唤醒词模型 - 检查第2步"; ok=0; }
echo "$LOG" | grep -q "State -> LISTENING" && echo "   [OK] 状态机进入 LISTENING" || { echo "   [FAIL] 状态机未到 LISTENING"; ok=0; }

echo ""
echo "================ MIUI 手点清单 (非小米跳过) ================"
echo "  1. 设置→应用设置→应用管理→Voice Assistant→权限管理→录音→【始终允许】"
echo "  2. 设置→应用设置→应用管理→Voice Assistant→自启动→【开】"
echo "  3. 设置→电池→省电流略→Voice Assistant→【无限制】"
echo "  完成后 force-stop 再启动一次应用，重新跑本脚本第5步验证。"
echo "============================================================"

[ $ok -eq 1 ] && echo "安装完成！说 '你好管家' 测试。装完可拔 USB。"
