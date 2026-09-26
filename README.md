# DSHA 手机虚拟副屏插件 (Phone Virtual Display)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2012%2B-green.svg)](https://developer.android.com)
[![DSHA](https://img.shields.io/badge/DSHA-0.1.5%2B-orange.svg)](https://github.com/deepseek-ai)

为手机端 **DSHA (DeepSeek for Android)** 打造的**独立虚拟副屏自动化与多任务运行插件**。

让手机端的 AI Agent 能够在后台独立创建并控制一块**看不见、不遮挡主屏**的虚拟屏幕（默认竖屏 720×1280、横屏 1280×720），在副屏中静默打开并操作任意第三方 App，实现“用户在前台使用主屏聊微信，AI 在后台副屏替用户操作设置或应用”的真正真机多任务体验！

---

## 🌟 核心特性

- 📱 **主副屏完全独立**：
  副屏上运行的应用拥有完全独立的显示层与输入焦点，不会抢占主屏前台焦点，不会遮挡用户视线。
- 🔓 **完美支持免 Root (Non-Root)**：
  经实测兼容 **Android 12 ~ Android 16**（覆盖 MIUI 13、RedMagic OS、AOSP、ColorOS 等多品牌定制系统）。在非 Root 设备上仅需通过 **ADB** 或 **Shizuku** 即可平稳运行；已 Root 设备亦可无缝兼容。
- 🌐 **Web 实时流式预览与手动接管**：
  内置原生 WebCodecs H.264 视频流（30 FPS），用户只需在手机浏览器中打开一次性安全链接，即可在主屏悬浮/分屏监视副屏画面，并可一键“手动接管”副屏触控输入，完成验证码或敏感密码输入后再交还 AI。
- 🤖 **原生 AI 工具集集成 (`vd_*`)**：
  向 DSHA 注册 10 项核心工具：`vd_start`（打开应用）、`vd_screenshot`（副屏识图）、`vd_tap` / `vd_swipe`（触控操作）、`vd_type` / `vd_key`（输入文字/按键）、`vd_preview`（预览接管）、`vd_stop`（安全退出）等。
- 🛡️ **安全隔离与本地优先**：
  默认所有通信局限在手机本地回环网络（`127.0.0.1:3096`），全程受 256 位安全 Token 鉴权保护，无需外网服务器，无数据泄露风险。

---

## 📁 干净的文件目录结构 (Repository Structure)

本项目已精简移除了所有开发过程中的临时脚本与私人凭据，保留纯粹标准的开源项目结构：

```text
dsh-virtual-display/
├── dist/                          # 📦 预打包发布文件（开箱即用）
│   └── dsh-virtual-display-0.5.0.tgz  # DSHA 离线插件包（直接导入 DSHA）
├── phone-plugin/                  # 🔌 DSHA 插件源码（Node.js / Cordis）
│   ├── package.json               # 插件元信息与依赖说明
│   ├── cordis.patch.yml           # DSH 插件注册补丁
│   └── lib/
│       ├── index.js               # 插件入口，定义与注册 vd_* 工具集
│       └── client.js              # 与手机本地 3096 守护进程通信的 HTTP 客户端
├── src/                           # ☕ 手机端 Helper 守护进程源码（Java）
│   ├── PhoneDisplay.java          # 核心控制器（内含 Android 12 ~ 16 自动兼容 flags）
│   └── VideoCapture.java          # MediaCodec 硬件视频流录制与 EGL Surface 绑定
├── ui/                            # 🖥️ Web 预览前端（零依赖原生 HTML5 / WebCodecs）
│   ├── viewer.html                # 预览页面
│   ├── viewer.js                  # 画面渲染与触摸输入事件反向转发
│   ├── viewer.css                 # 响应式布局样式
│   └── video.js                   # WebCodecs 视频流解码器
├── vendor/                        # 📚 第三方底层运行库与编译依赖
│   ├── scrcpy.jar                 # Genymobile scrcpy-server v4.1（Android 系统服务适配层）
│   ├── r8.jar                     # Google D8 编译器（编译 class -> dex）
│   └── Workarounds.java           # Android 底层反射兼容工作区
├── build/                         # 🔨 编译产物目录
│   ├── dsh-vdisplay.jar           # 预编译好的 Helper 核心 Dex 包（内置，直接可跑）
│   └── android.jar                # Android SDK API 桩文件（供二次编译使用）
├── scripts/                       # 🛠️ 自动化运维与激活脚本
│   ├── activate.cmd               # Windows 电脑端一键 ADB 部署与激活脚本
│   ├── activate.sh                # Linux / macOS 电脑端一键 ADB 部署与激活脚本
│   ├── start.sh                   # 手机端常驻服务启动脚本（供 ADB / Shizuku 执行）
│   ├── build-local.ps1            # Windows 本地二次编译打包脚本（Java -> Dex -> Jar）
│   ├── build.sh                   # Linux / Docker 环境编译脚本
│   └── verify-helper-jar.ps1      # Jar 包 DEX 合规性校验脚本
├── .gitignore                     # Git 忽略配置（已忽略临时 state 和 log）
├── LICENSE                        # 开源协议 (Apache-2.0)
└── THIRD-PARTY-LICENSE.txt        # 第三方组件许可证声明
```

---

## 🚀 安装与使用指南

### 第一步：手机端安装 DSHA 插件

1. 将 `dist/dsh-virtual-display-0.5.0.tgz` 拷贝到手机存储（例如 `/sdcard/Download/` 目录）；
2. 打开手机上的 **DSHA** 应用；
3. 进入 DSHA 内置的简易终端（Terminal），依次执行以下命令：
   ```bash
   # 1. 导入虚拟副屏插件
   dsha-plugin import /sdcard/Download/dsh-virtual-display-0.5.0.tgz

   # 2. 配置通信 Token（创建配置目录并写入 token）
   mkdir -p /root/.dsh
   cat > /root/.dsh/virtual-display.json <<'EOF'
   {"token":"LyHEk15xrv9DfO6YF4ng5qrsbg27JBZN7SHszTlh6Po"}
   EOF
   ```
4. 在 DSHA 主界面点击重启，或查看启动日志出现 `插件加载完成: @local/dsh-virtual-display` 即代表插件就绪。

---

### 第二步：激活手机副屏服务（支持三种途径）

副屏后台服务需以系统特权（`Shell UID 2000` 或 `Root UID 0`）运行，我们提供了三种最简方案：

#### 方案 A：使用电脑一键自动激活（推荐，最省心）
1. 手机开启「开发者选项」中的 **「USB 调试」**（或「无线调试」）；
2. 手机使用数据线连接电脑（或者与电脑连接同一 Wi-Fi 后执行 `adb connect <手机IP>:<端口>`）；
3. 直接在本项目根目录运行激活脚本：
   - **Windows**：双击运行 `scripts\activate.cmd`
   - **macOS / Linux**：终端运行 `./scripts/activate.sh`
4. 脚本会自动将核心组件推送到手机 `/data/local/tmp/dsh-phone-vdisplay/` 并在后台独立启动常驻服务。

#### 方案 B：纯手机脱机激活（免电脑，使用 Shizuku）
如果你在户外没有电脑，可以通过 [Shizuku](https://shizuku.rikka.app/) 在手机本地激活：
1. 手机安装并启动 Shizuku（已授权无线调试）；
2. 使用任何支持 Shizuku 调用的终端应用（如 Termux 配套 `shizuku-exec`，或 Shizuku Runner），直接执行：
   ```bash
   sh /data/local/tmp/dsh-phone-vdisplay/start.sh
   ```
3. 即可在手机本地直接拉起后台副屏服务，不需要连接电脑！

#### 方案 C：Root 手机开机自启
如果手机已 Root（Magisk / KernelSU / APatch）：
只需将启动命令放入 Magisk 自启目录：
```bash
su -c "echo 'sh /data/local/tmp/dsh-phone-vdisplay/start.sh' > /data/adb/service.d/vdisplay.sh && chmod +x /data/adb/service.d/vdisplay.sh"
```
手机每次开机，副屏服务便会自动在后台静默就绪！

---

### 第三步：日常使用与对话测试

打开手机 DSHA，直接在对话框中用自然语言对 DeepSeek 发送指令即可：

- **打开应用并在副屏操作**：
  > “用虚拟副屏打开设置，帮我看看电池还剩多少电。”  
  > “在副屏打开应用商店，搜索微信。”
- **查看与手动接管画面**：
  > “打开副屏预览，我要查看画面。”  
  DSHA 会调用 `vd_preview`，并在手机浏览器中自动打开本地预览页面。你可以一边看着实时画面，一边点击「手动接管」亲自在副屏上打字或滑动，交还控制后 AI 会继续后续流程。
- **关闭副屏**：
  > “关闭虚拟副屏。”

---

## 🛠️ 二次开发与源码编译

本项目已内置经过验证的预编译 `dsh-vdisplay.jar`，一般用户无需自己编译。如果开发者修改了 `src/` 中的 Java 代码，可以通过以下方式快速重新构建：

### Windows 环境编译：
需安装 JDK 17 或更新版本，在项目根目录运行：
```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-local.ps1
```
脚本会自动调用 JDK 的 `javac`、Google `r8.jar`（D8 编译器），一键生成 DEX 并打成合规的 `build/dsh-vdisplay.jar`。

### Linux / Docker 环境编译：
```bash
sh scripts/build.sh
```

---

## ❓ 常见问题排查 (FAQ)

### Q1: 提示 `SecurityException: createVirtualDisplay() requires android.permission.ADD_TRUSTED_DISPLAY`？
**解答**：这是部分定制系统（如小米 MIUI 13）在 Android 12 上对独立屏幕 flags 的拦截。本项目最新版本已在 `PhoneDisplay.java` 中加入了版本动态自适应降级逻辑（在 Android 12 下自动使用兼容 flag `0x1cb`），已在 MIUI 13 实测彻底解决。

### Q2: 手机重启后副屏不工作了？
**解答**：Android 系统在关机或重启时会重置 `/data/local/tmp` 中的临时运行进程。重启后只需再次连接电脑运行一次 `activate.cmd`，或在手机上通过 Shizuku 执行一次 `sh /data/local/tmp/dsh-phone-vdisplay/start.sh` 即可迅速复活。

---

## 📄 许可证与致谢

- 本项目遵循 [Apache-2.0 License](LICENSE) 开源协议。
- 虚拟显示驱动包装层参考并使用了 [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) 的底层 Wrapper 架构与 Workarounds（遵循 Apache-2.0 许可证，详见 `THIRD-PARTY-LICENSE.txt`）。
- 感谢 DeepSeek 团队为 Android 带来的强大端侧智能平台。
