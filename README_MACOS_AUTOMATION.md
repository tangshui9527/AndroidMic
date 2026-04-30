# AndroidMic macOS 自动化全手册 (最新版)

## 🚀 核心功能：全自动无线麦克风
现在你可以通过电脑一键控制手机，自动打开 App 并建立连接，将手机作为电脑的低延迟无线麦克风。

---

## 🛠️ 快速开始

### 1. 安装手机端 APK
首先将包含最新自动连接逻辑的 APK 安装到手机：
```bash
adb install -r Android/app/build/outputs/apk/debug/app-debug.apk
```

### 2. 运行一键启动脚本 (推荐)
直接在终端运行脚本。它会自动连接 ADB、启动电脑端、同步 IP 并触发手机点击：
```bash
./start_android_remote.sh
```

### 3. 使用 macOS 标准 App
你也可以在 Finder 中直接运行 **AndroidMic.app**。你可以将它拖入 `/Applications`（应用程序）文件夹，像普通软件一样使用。

---

## 📂 关键文件说明

- **`start_android_remote.sh`**: 核心自动化脚本，实现电脑控制手机的全过程。
- **`AndroidMic.app`**: 编译好的 macOS 标准应用程序包。
- **`package_macos.sh`**: 开发者工具，用于重新编译并生成最新的 `.app` 包。
- **`Android/app/build/outputs/apk/debug/app-debug.apk`**: 手机端最新安装包。

---

## ✨ 已实现的优化

### 💻 电脑端 (RustApp)
1. **状态面板 (Status Panel)**：实时显示连接状态和**手机端真实 IP**。
2. **自动化参数**：新增 `--listen` 参数，支持启动即进入监听。
3. **更清晰的标签**：UI 界面中的连接模式已汉化并重命名为更直观的 `Wi-Fi`、`USB 隧道 (ADB)` 等。
4. **稳定性修复**：解决了音频设备不匹配导致的崩溃问题。

### 📱 手机端 (Android)
1. **Intent 触发自动连接**：接收电脑端指令，自动覆盖 IP 配置并执行“点击连接”。
2. **DataStore 单例化**：彻底修复了因多个实例冲突导致的“DataStore conflict”闪退。
3. **TCP 握手优化**：引入循环读取和 UTF-8 编码，解决了连接时可能出现的 `Read timed out` 错误。

---

## 🔧 进阶：如何重新打包

如果你将来修改了代码，可以使用以下命令重新编译：

- **重新生成电脑端 App**:
  ```bash
  ./package_macos.sh
  ```
- **重新编译手机端 APK**:
  ```bash
  cd Android && ./gradlew :app:assembleDebug
  ```

---

## 📝 注意事项
- 确保手机与电脑在同一 Wi-Fi 网络下。
- 手机需开启 ADB 无线调试。
- 脚本中默认的手机 IP 为 `192.168.31.6`，如有变动请在脚本配置区修改。

---
*文档维护者: Trae AI*
*日期: 2026-04-20*
