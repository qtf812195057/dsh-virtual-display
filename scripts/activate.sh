#!/usr/bin/env bash
set -euo pipefail

echo "========================================================"
echo "  DSHA 手机虚拟副屏助手 (Phone Virtual Display) 激活程序"
echo "========================================================"
echo ""

# 1. 检查 ADB 命令
if ! command -v adb >/dev/null 2>&1; then
    echo "[错误] 未在系统 PATH 中找到 adb 命令！请先安装 Android platform-tools。"
    exit 1
fi

# 2. 检查设备
DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
DEVICE_COUNT=$(echo "$DEVICES" | grep -c . || true)

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "[错误] 未检测到已授权的 Android 设备！"
    echo "请开启手机开发者选项与 USB/无线调试并允许电脑连接。"
    exit 1
fi

TARGET_SERIAL=$(echo "$DEVICES" | head -n 1)
echo "[*] 目标设备: $TARGET_SERIAL"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
REMOTE_DIR="/data/local/tmp/dsh-phone-vdisplay"

# 3. 创建手机目录
echo "[*] 创建手机工作目录 $REMOTE_DIR ..."
adb -s "$TARGET_SERIAL" shell "mkdir -p $REMOTE_DIR && chmod 755 $REMOTE_DIR"

# 4. 生成或读取凭据 Token
mkdir -p "$ROOT_DIR/state"
if [ ! -f "$ROOT_DIR/state/token" ]; then
    head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=' | head -c 43 > "$ROOT_DIR/state/token"
fi
TOKEN=$(cat "$ROOT_DIR/state/token")

# 5. 推送组件
echo "[*] 推送核心服务组件..."
adb -s "$TARGET_SERIAL" push "$ROOT_DIR/vendor/scrcpy.jar" "$REMOTE_DIR/scrcpy.jar" >/dev/null
adb -s "$TARGET_SERIAL" push "$ROOT_DIR/build/dsh-vdisplay.jar" "$REMOTE_DIR/helper.jar" >/dev/null
adb -s "$TARGET_SERIAL" push "$ROOT_DIR/ui/viewer.html" "$REMOTE_DIR/viewer.html" >/dev/null
adb -s "$TARGET_SERIAL" push "$ROOT_DIR/ui/viewer.js" "$REMOTE_DIR/viewer.js" >/dev/null
adb -s "$TARGET_SERIAL" push "$ROOT_DIR/ui/viewer.css" "$REMOTE_DIR/viewer.css" >/dev/null
adb -s "$TARGET_SERIAL" push "$ROOT_DIR/ui/video.js" "$REMOTE_DIR/video.js" >/dev/null
adb -s "$TARGET_SERIAL" push "$ROOT_DIR/scripts/start.sh" "$REMOTE_DIR/start.sh" >/dev/null
adb -s "$TARGET_SERIAL" push "$ROOT_DIR/state/token" "$REMOTE_DIR/token" >/dev/null

adb -s "$TARGET_SERIAL" shell "chmod 755 $REMOTE_DIR/start.sh && chmod 644 $REMOTE_DIR/*.* $REMOTE_DIR/token"

# 6. 推送配置文件与插件到手机 Download 目录
TEMP_JSON=$(mktemp)
echo "{\"token\":\"$TOKEN\"}" > "$TEMP_JSON"
adb -s "$TARGET_SERIAL" push "$TEMP_JSON" /sdcard/Download/virtual-display.json >/dev/null
rm -f "$TEMP_JSON"

if [ -f "$ROOT_DIR/dist/dsh-virtual-display-0.5.0.tgz" ]; then
    echo "[*] 推送 DSHA 插件包到手机 /sdcard/Download/ ..."
    adb -s "$TARGET_SERIAL" push "$ROOT_DIR/dist/dsh-virtual-display-0.5.0.tgz" /sdcard/Download/dsh-virtual-display-0.5.0.tgz >/dev/null
fi

# 7. 启动服务
echo "[*] 启动手机端副屏服务..."
adb -s "$TARGET_SERIAL" shell "sh $REMOTE_DIR/start.sh"

sleep 2
if adb -s "$TARGET_SERIAL" shell "ps -A" | grep -q "PhoneDisplay"; then
    echo ""
    echo "========================================================"
    echo "  [成功] DSHA 手机虚拟副屏服务已成功在手机后台启动！"
    echo "========================================================"
    echo ""
    echo "接下来在手机 DSHA 中完成插件导入（仅首次需要）："
    echo "  1. 打开手机端 DSHA 应用；"
    echo "  2. 进入内置终端，依次执行以下命令："
    echo ""
    echo "     dsha-plugin import /sdcard/Download/dsh-virtual-display-0.5.0.tgz"
    echo "     mkdir -p /root/.dsh"
    echo "     cp /sdcard/Download/virtual-display.json /root/.dsh/virtual-display.json"
    echo ""
    echo "  3. 重启 DSHA 核心后即可直接对话测试副屏！"
    echo ""
else
    echo "[警告] 服务进程未检测到，日志如下："
    adb -s "$TARGET_SERIAL" shell "cat $REMOTE_DIR/service.log"
fi
