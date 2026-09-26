@echo off
setlocal enabledelayedexpansion

echo ========================================================
echo   DSHA 手机虚拟副屏助手 (Phone Virtual Display) 激活程序
echo ========================================================
echo.

:: 1. 检查 ADB 环境
where adb >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未在系统 PATH 中找到 adb 命令！
    echo 请先安装 Android Platform Tools (ADB) 并将其目录加入系统环境变量 PATH。
    pause
    exit /b 1
)

:: 2. 检查设备连接
echo [*] 正在检测已连接的 Android 设备...
set TEMP_DEV="%temp%\dsh_adb_dev_%random%.txt"
adb devices > %TEMP_DEV%
set DEVICE_COUNT=0
set TARGET_SERIAL=

for /f "skip=1 tokens=1,2" %%i in ('type %TEMP_DEV%') do (
    if "%%j"=="device" (
        set /a DEVICE_COUNT+=1
        set TARGET_SERIAL=%%i
    )
)
del %TEMP_DEV% 2>nul

if %DEVICE_COUNT% equ 0 (
    echo [错误] 未检测到已授权连接的 Android 设备！
    echo 请检查：
    echo   1. 手机是否已开启「开发者选项」及「USB 调试」或「无线调试」；
    echo   2. 手机端弹出「允许调试」授权提示时是否已点击允许；
    echo   3. 命令行运行 adb devices 是否能看到状态为 device 的设备。
    pause
    exit /b 1
)

echo [*] 找到目标设备: %TARGET_SERIAL%
echo.

:: 3. 准备手机端目录
set REMOTE_DIR=/data/local/tmp/dsh-phone-vdisplay
echo [*] 创建手机工作目录 %REMOTE_DIR% ...
adb -s %TARGET_SERIAL% shell "mkdir -p %REMOTE_DIR% && chmod 755 %REMOTE_DIR%"

:: 4. 生成或读取通信凭据 Token
set ROOT_DIR=%~dp0..
if not exist "%ROOT_DIR%\state" mkdir "%ROOT_DIR%\state" 2>nul
if not exist "%ROOT_DIR%\state\token" (
    powershell -NoProfile -Command "[Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).TrimEnd('=').Replace('+','-').Replace('/','_')" > "%ROOT_DIR%\state\token"
)
set /p TOKEN=<"%ROOT_DIR%\state\token"
set TOKEN=%TOKEN: =%

:: 5. 推送核心文件到手机
echo [*] 正在推送核心服务组件 (helper.jar, scrcpy.jar, UI 文件)...
adb -s %TARGET_SERIAL% push "%ROOT_DIR%\vendor\scrcpy.jar" %REMOTE_DIR%/scrcpy.jar >nul
adb -s %TARGET_SERIAL% push "%ROOT_DIR%\build\dsh-vdisplay.jar" %REMOTE_DIR%/helper.jar >nul
adb -s %TARGET_SERIAL% push "%ROOT_DIR%\ui\viewer.html" %REMOTE_DIR%/viewer.html >nul
adb -s %TARGET_SERIAL% push "%ROOT_DIR%\ui\viewer.js" %REMOTE_DIR%/viewer.js >nul
adb -s %TARGET_SERIAL% push "%ROOT_DIR%\ui\viewer.css" %REMOTE_DIR%/viewer.css >nul
adb -s %TARGET_SERIAL% push "%ROOT_DIR%\ui\video.js" %REMOTE_DIR%/video.js >nul
adb -s %TARGET_SERIAL% push "%ROOT_DIR%\scripts\start.sh" %REMOTE_DIR%/start.sh >nul
adb -s %TARGET_SERIAL% push "%ROOT_DIR%\state\token" %REMOTE_DIR%/token >nul

adb -s %TARGET_SERIAL% shell "chmod 755 %REMOTE_DIR%/start.sh && chmod 644 %REMOTE_DIR%/*.* %REMOTE_DIR%/token"

:: 6. 生成并推送 DSHA 插件配置文件到 Download 目录
set CONFIG_TEMP="%temp%\virtual-display.json"
echo {"token":"%TOKEN%"} > %CONFIG_TEMP%
adb -s %TARGET_SERIAL% push %CONFIG_TEMP% /sdcard/Download/virtual-display.json >nul
del %CONFIG_TEMP% 2>nul

:: 7. 推送 DSHA 插件安装包到 Download 目录
if exist "%ROOT_DIR%\dist\dsh-virtual-display-0.5.0.tgz" (
    echo [*] 推送 DSHA 插件包到手机 /sdcard/Download/ ...
    adb -s %TARGET_SERIAL% push "%ROOT_DIR%\dist\dsh-virtual-display-0.5.0.tgz" /sdcard/Download/dsh-virtual-display-0.5.0.tgz >nul
)

:: 8. 启动手机端副屏服务
echo [*] 启动手机端副屏后台守护服务 (127.0.0.1:3096)...
adb -s %TARGET_SERIAL% shell "sh %REMOTE_DIR%/start.sh"

:: 9. 验证运行状态
echo [*] 验证副屏服务运行状态...
timeout /t 2 >nul
set PS_TEMP="%temp%\ps_check_%random%.txt"
adb -s %TARGET_SERIAL% shell "ps -A | grep PhoneDisplay" > %PS_TEMP%
findstr /i "PhoneDisplay" %PS_TEMP% >nul
if %errorlevel% equ 0 (
    echo.
    echo ========================================================
    echo   [成功] DSHA 手机虚拟副屏服务已成功在手机后台启动！
    echo ========================================================
    echo.
    echo 接下来在手机 DSHA 中完成插件导入（仅首次需要）：
    echo   1. 打开手机端 DSHA 应用；
    echo   2. 进入内置终端，依次执行以下命令：
    echo.
    echo      dsha-plugin import /sdcard/Download/dsh-virtual-display-0.5.0.tgz
    echo      mkdir -p /root/.dsh
    echo      cp /sdcard/Download/virtual-display.json /root/.dsh/virtual-display.json
    echo.
    echo   3. 重启 DSHA 核心或点击「进入」新建会话，即可直接对机器人说：
    echo      「用虚拟副屏打开设置」
    echo      「打开副屏预览，我要查看画面」
    echo.
) else (
    echo.
    echo [警告] 未检测到运行进程，请查看日志输出：
    adb -s %TARGET_SERIAL% shell "cat %REMOTE_DIR%/service.log"
)
del %PS_TEMP% 2>nul
pause
