#!/bin/sh
# DSHA 虚拟副屏后台常驻服务启动脚本
# 适用平台：Android 12 ~ Android 16 (支持免特权 Shell UID 2000 及 Root UID 0)

BASE=/data/local/tmp/dsh-phone-vdisplay

# 1. 结束已有旧实例
pkill -f '[l]ocal.dsh.vdisplay.PhoneDisplay' 2>/dev/null || true
sleep 0.5

# 2. 确保 token 凭据存在
if [ ! -f "$BASE/token" ]; then
    if [ -f /dev/urandom ]; then
        head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=' | head -c 43 > "$BASE/token"
    else
        echo "LyHEk15xrv9DfO6YF4ng5qrsbg27JBZN7SHszTlh6Po" > "$BASE/token"
    fi
    chmod 600 "$BASE/token" 2>/dev/null || true
fi

# 3. 使用 setsid 独立运行 app_process，防止调用终端关闭时被系统连带杀掉
setsid sh -c "CLASSPATH=$BASE/scrcpy.jar:$BASE/helper.jar app_process / local.dsh.vdisplay.PhoneDisplay $BASE/token" > "$BASE/service.log" 2>&1 </dev/null &

echo "DSHA Virtual Display Helper started. PID: $!"
