# 新手机安装指南（Voice Assistant）

在另一台手机上部署本项目的完整步骤。基于 2026-08-18 在小米 MI 8 UD（MIUI 12.5）上的
实战经验整理，**加粗的坑是 MIUI 特有的，普通 Android 可以跳过**。

## 一、前置条件

| 项目 | 要求 |
|------|------|
| 手机 | Android 8.0+（API 26+），能开 USB 调试 |
| 电脑 | 运行 Bridge（Mac/Linux），与手机**同一 WiFi** |
| 线缆 | USB 数据线（只在安装阶段用，装完可拔） |
| 电脑工具 | `adb`、JDK 17、Android SDK（要现场构建 APK 才需要） |

## 二、Mac 上启动 Bridge

```bash
cd bridge
python3 -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

确认 Mac 的局域网 IP：`ipconfig getifaddr en0`（如 `192.168.1.116`）。

验证：`curl http://<Mac IP>:8000/v1/health` → `{"ok":true,...}`

> 长期运行建议装成 launchd 服务（见仓库 `~/Library/LaunchAgents/com.gaozhi.voice-bridge.plist`），
> 崩溃自动拉起 + 开机自启。

## 三、安装 APK

**方式 A：用现成 APK**（仓库外已构建好的 `app-debug.apk`）

**方式 B：现场构建**

```bash
cd android
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
./gradlew assembleDebug
# 产物: android/app/build/outputs/apk/debug/app-debug.apk
```

**安装前**：确认 `android/app/build.gradle.kts` 里的 `BRIDGE_BASE_URL` 指向你的 Mac IP
（默认 `http://192.168.1.116:8000`）。改完需要重新构建。

**USB 连接手机后**：

```bash
adb install -r app-debug.apk
```

**坑 1：MIUI 弹"继续安装"确认框**，5 秒倒计时默认拒绝。自动点掉：

```bash
adb install -r app-debug.apk &
sleep 3.5
adb shell input tap 300 2020   # 点"继续安装"
```

**坑 2：MIUI 需要开启"允许安装未知应用"**（开发者选项里），否则直接失败。

## 四、权限（MIUI 特有，最关键的部分）

**坑 3：麦克风权限必须设为"始终允许"，普通"允许"不够！**

MIUI 的隐私层会拦：`pm grant` 之后 `dumpsys` 显示 granted，但录音时
AudioFlinger 报 `getInputForAttr error -1`。必须走设置界面：

> 设置 → 应用设置 → 应用管理 → Voice Assistant → 权限管理 → 录音 → **始终允许**

命令行无法设置这个 MIUI 隐私级，必须手点（或用 uiautomator 模拟点击）。

**坑 4：自启动 + 电池优化**

> 设置 → 应用设置 → 应用管理 → Voice Assistant → 自启动 → 开
> 设置 → 电池 → 省电流略 → Voice Assistant → **无限制**

不做这两步，锁屏一段时间后服务会被 MIUI 杀掉。

**坑 5：通知权限**（前台服务需要）：授权通知。

## 五、推送唤醒词模型（Vosk 中文，约 1.3G）

模型放在 Mac 上（`/tmp/vosk-model-cn-0.22.zip` 或自行从
https://alphacephei.com/vosk/models 下载 `vosk-model-cn-0.22`）。

模型 zip 解开的目录结构是 `vosk-model-cn-0.22/`，需要放进 app 的 filesDir。
filesDir 没有 shell 写权限，用 `run-as`（仅 debug 包可用）：

```bash
unzip -q /tmp/vosk-model-cn-0.22.zip   # 得到 vosk-model-cn-0.22/
adb push vosk-model-cn-0.22.tar /data/local/tmp/  # 先 tar 打包
tar -cf /tmp/model.tar vosk-model-cn-0.22
adb push /tmp/model.tar /data/local/tmp/
adb shell "run-as com.example.voiceassistant sh -c 'cd files && tar -xf /data/local/tmp/model.tar'"
adb shell rm /data/local/tmp/model.tar
```

## 六、激活 TTS 引擎（仅小米手机）

小米自带引擎（MiBrain）注册了 TTS 服务但默认未激活，`onInit` 返回 -1。
显式设为默认引擎即可激活，**无需安装任何应用**：

```bash
adb shell settings put secure tts_default_speaker \
  com.xiaomi.mibrain.speech/com.xiaomi.mibrain.speech.tts.TtsService
```

其他品牌手机：装一个第三方 TTS 引擎（如讯飞语音引擎）并设为默认即可，
app 会自动走原生通道；没有可用引擎时 app 回退到在线 TTS（需要国际网络，延迟 1-3 秒）。

## 七、启动并验证

```bash
adb shell am start -n com.example.voiceassistant/.ui.MainActivity
adb logcat | grep -E "AudioRecord|Vosk|State|TtsManager"
```

健康标志（依次出现）：

```
AudioRouter: AudioRecord started successfully
VoskWakeWordEngine: Vosk model loaded successfully
VoiceSession: State -> LISTENING
```

对着手机说 **"你好管家"**，然后说任务。手机 → Mac 全链路走 WiFi，
正常 5-10 秒内开始播报；长任务先播"正在查询，请稍后"，答案 2-4 秒内补播。

## 八、日常使用

- **拔掉 USB 线**，一切照旧（语音主链路 + 迟到回复轮询都是 WiFi）
- Mac 休眠时不可用，唤醒后自动恢复
- 手机设置里保持"录音=始终允许"和电池"无限制"

## 一键脚本

`tools/install_phone.sh` 自动化第三、五、六、七节（MIUI 手点项除外）：

```bash
./tools/install_phone.sh              # 使用 adb 当前唯一设备
./tools/install_phone.sh <serial>     # 多设备时指定
```

脚本会打印出仍需手动完成的 MIUI 步骤清单。
