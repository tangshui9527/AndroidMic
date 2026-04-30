#!/bin/bash
# AndroidMic 远程自动化启动脚本

# --- 配置 ---
PHONE_IP="192.168.31.6"
ADB_PORT="5555"
PACKAGE_NAME="io.github.teamclouday.AndroidMic.debug"
MAIN_ACTIVITY="io.github.teamclouday.androidMic.ui.MainActivity"
SERVICE_NAME="io.github.teamclouday.androidMic.domain.service.ForegroundService"
AUTO_CONNECT_ACTION="io.github.teamclouday.androidMic.AUTO_CONNECT"

# --- 自动检测本机 IP ---
LOCAL_IP=$(ipconfig getifaddr en1)
if [ -z "$LOCAL_IP" ]; then
    LOCAL_IP=$(ipconfig getifaddr en0)
fi
PORT="54345"

echo "----------------------------------------"
echo "💻 本机 IP: $LOCAL_IP"
echo "📱 手机 IP: $PHONE_IP"
echo "----------------------------------------"

# 1. 尝试连接 ADB
adb connect $PHONE_IP:$ADB_PORT

# 2. 启动桌面端 (确保 GUI 打开并进入监听状态)
echo "💻 启动桌面端 App..."
# 使用 open 确保在 macOS 上打开 GUI 窗口，并传递参数
# 如果 open 不支持传参，我们直接后台运行二进制
pkill -9 android-mic || true
/Users/tangshui/Desktop/AndroidMic/RustApp/target/debug/android-mic -i $LOCAL_IP -m TCP --listen &

echo "⏳ 等待桌面端初始化 (5s)..."
sleep 5

# 3. 启动手机端 App 并强制设置 IP/Port
echo "🚀 远程开启手机 App 并同步配置..."
adb shell am start -n $PACKAGE_NAME/$MAIN_ACTIVITY \
    --ez auto_connect true \
    --es mode "WIFI" \
    --es ip "$LOCAL_IP" \
    --es port "$PORT"

# 4. 辅助：启动前台服务 (防止 Activity 启动失败)
echo "🛠️ 启动后台同步服务..."
adb shell am start-foreground-service -n $PACKAGE_NAME/$SERVICE_NAME -a $AUTO_CONNECT_ACTION \
    --ez auto_connect true \
    --es mode "WIFI" \
    --es ip "$LOCAL_IP" \
    --es port "$PORT"

echo "----------------------------------------"
echo "✅ 全自动化流程已触发！"
echo "请观察电脑端和手机端是否已成功连接。"
echo "----------------------------------------"
