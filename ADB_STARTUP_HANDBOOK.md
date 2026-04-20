# AndroidMic ADB 启动手册 (已优化)

## 愿景与需求

### 核心目标
将 **Android 手机** (如 Xiaomi 6) 作为电脑的 **无线麦克风**，追求 **极低延迟** 与 **全自动化启动**。

### 自动化梦想
> 电脑一键 "Start"，手机端自动启动、自动连接，无需任何手动点击。

---

## 当前状态 (已修复 & 优化)

### 已解决的问题
1. **握手失败 (Handshake Error)**: 已修复 `TcpStreamer.kt` 中的握手逻辑，增加了 UTF-8 编码指定和循环读取保证，确保 TCP 连接稳定性。
2. **DataStore 冲突**: 已修复 `ForegroundService.kt` 中重复创建 `AppPreferences` 的问题，改用单例模式，彻底解决 "Multiple DataStore instances" 错误。
3. **自动化增强**: 桌面端 Rust App 已支持 `--listen` 参数，启动即进入监听状态。

### 当前架构
```
PC (Rust App) --[Listen]--> 等待连接
PC (ADB) --[Command]--> Android (ForegroundService)
Android --[Connect]--> PC (TCP Handshake)
Connection Established -> Audio Streaming
```

---

## 自动化启动流程 (推荐)

现在你只需要运行一个简单的脚本即可完成所有操作，不再需要模拟点击屏幕。

### 1. 准备工作
确保手机已开启 ADB 调试，并与电脑在同一网络。

### 2. 优化后的启动脚本 (`start_mic.sh`)

```bash
#!/bin/bash

# --- 配置区 ---
PC_IP="192.168.31.88"
PHONE_IP="192.168.31.6"
ADB_PORT="5555"
RUST_APP_PATH="/Users/tangshui/Desktop/AndroidMic/RustApp/target/debug/android-mic"
# --- --- --- ---

echo "1. 启动桌面端监听..."
# 启动桌面端并自动开启监听 (--listen)
$RUST_APP_PATH -i $PC_IP -m TCP --listen &
SLEEP_PID=$!

echo "2. 连接 ADB..."
adb connect $PHONE_IP:$ADB_PORT

echo "3. 启动 Android 前台服务..."
# 发送 AUTO_CONNECT 信号，服务启动后会自动读取配置并连接 PC
adb shell am start-foreground-service \
  -n io.github.teamclouday.AndroidMic.debug/io.github.teamclouday.androidMic.domain.service.ForegroundService \
  -a io.github.teamclouday.androidMic.AUTO_CONNECT

echo "🚀 启动完成！等待几秒即可直接说话。"
```

---

## 关键代码位置 (供开发者参考)

- **Android 手动/自动连接逻辑**: [ForegroundService.kt](file:///Users/tangshui/Desktop/AndroidMic/Android/app/src/main/java/io/github/teamclouday/androidMic/domain/service/ForegroundService.kt)
- **TCP 握手实现**: [TcpStreamer.kt](file:///Users/tangshui/Desktop/AndroidMic/Android/app/src/main/java/io/github/teamclouday/androidMic/domain/streaming/TcpStreamer.kt)
- **桌面端 CLI 参数处理**: [config.rs](file:///Users/tangshui/Desktop/AndroidMic/RustApp/src/config.rs)
- **桌面端自动监听逻辑**: [app.rs](file:///Users/tangshui/Desktop/AndroidMic/RustApp/src/ui/app.rs)

---

## 进阶技巧：开机自启 (Magisk 方案)

如果你有 Root 权限，可以使用提供的 Magisk 模块，实现手机开机即等待 PC 指令。

1. 安装 `MagiskModule/` 目录下的模块。
2. 该模块会监听系统启动并准备好接收 ADB 指令。

---

## 联系与反馈
- 修复人: Trae AI
- 日期: 2026-04-20
