# DSHA 手机虚拟副屏 0.5.1

在 Android 手机上创建独立虚拟显示屏，让 DSHA 在副屏打开应用、截图和操作，同时保留主屏供用户使用。提供浏览器预览、H.264 视频和手动接管。

**0.5.1 为手机本地独立版：安装、启动和日常使用都可以在手机完成，不需要电脑服务、远程网关、FRP 或其他项目目录。** 插件只连接本机 `127.0.0.1:3096`；Helper 退出时提示用户自行恢复，不会请求任何电脑，也不会自动重试操作。

“独立”不代表普通插件能够自行获得 Android 系统权限。用户仍需自行准备 **Shizuku 授权的 Android 终端、手机本地 ADB shell，或 root shell**。这些工具的安装、授权、无线调试配对和重启后恢复由用户负责，本项目不自动配置它们。

## 下载

- **完整手机安装包：[dsh-vdisplay-phone-0.5.1.zip](dist/dsh-vdisplay-phone-0.5.1.zip)**。包含预编译 Helper、scrcpy 运行库、网页、手机安装/启动脚本和 DSHA 插件包。
- [单独的 DSHA 插件包](dist/dsh-virtual-display-0.5.1.tgz)：仅适合已经部署好 Helper 的用户。只导入这个包不会安装或启动 Helper。
- [SHA256 校验值](dist/SHA256SUMS)。安装脚本也会校验完整包里的运行文件。

GitHub 手机上打开文件后选择下载原文件。ZIP 解压后目录应为 `dsh-vdisplay-0.5.1`，其中直接包含 `install-phone.sh`、`helper.jar` 等文件。

## 适用范围

- 需要支持 `@deepseek-ai/dsh-tools ^0.1.5-rc.2` 的手机 DSHA，以及其终端内可用的 Node.js。
- 主要实测环境：红魔 NX789J / Android 16。代码含旧 Android 显示标志兼容分支，但不能据此保证 Android 12–16 的所有品牌、ROM 或应用都支持。
- 首次启动权限通道需要用户操作。Shizuku 官方提供 Android 11 及以上通过无线调试在手机自行启动的方法；非 root 方式在手机重启后需要重新启动 Shizuku。参见[官方启动说明](https://shizuku.rikka.app/guide/setup/)。
- 部分应用不支持副屏；单实例应用可能被系统从主屏移到副屏。输入或显示创建被 ROM 拒绝时请停止，不要自动改用主屏。
- 本地副屏通信不需要互联网；DSHA 的模型服务是否需要联网，取决于用户自己的模型配置。

## 完全在手机安装

### 1. 准备有权限的 Android 终端

自行选择一种方式：

- Shizuku：按其官方文档开启开发者选项、无线调试并完成配对、启动，再授权支持 Shizuku 的终端。使用 rish 的用户参见[官方 rish 说明](https://github.com/RikkaApps/Shizuku-API/blob/master/rish/README.md)。只安装 Shizuku 而未启动、未授权终端是不够的。
- 手机本地 ADB：自行使用支持本机无线调试配对的工具，进入本机 Android shell。
- root：在自己信任的终端进入 root shell。

在该终端执行 `id`，应有 Android shell UID **2000** 或 root UID **0**，并能访问 `/system/bin/app_process`。**DSHA 内的 Linux 终端即使显示 root，也不等于 Android root shell。** 不要在普通 DSHA 终端执行安装和启动脚本。

### 2. 解压完整包

用手机文件管理器将 ZIP 解压到：

```text
/sdcard/Download/dsh-vdisplay-0.5.1/
```

如果手机的共享存储映射不同，以下所有命令均使用实际路径。不要解压到只有某个应用自己可访问的私有目录。

### 3. 在 DSHA 终端生成配置并导入插件

```sh
node /sdcard/Download/dsh-vdisplay-0.5.1/configure.mjs
dsha-plugin import /sdcard/Download/dsh-vdisplay-0.5.1/dsh-virtual-display-0.5.1.tgz
```

也可通过 DSHA 的插件管理界面导入 `.tgz`，但仍须运行配置脚本。

`configure.mjs` 在 `$DSH_HOME/virtual-display.json`（默认 `~/.dsh/virtual-display.json`）生成每位用户独有的随机凭据。已有合法配置会复用，不会重置。没有共享默认密码，也不会打印凭据。

脚本在解压目录生成临时 `virtual-display.setup.json`，供下一步传递配置。这个文件在共享存储中，安装后会删除；请尽快完成下一步，不要分享或上传它。安装失败时，可删除此临时文件后重新运行配置脚本再试。

### 4. 在有权限的 Android 终端安装并启动

```sh
sh /sdcard/Download/dsh-vdisplay-0.5.1/install-phone.sh
```

脚本校验文件、将组件安装到 `/data/local/tmp/dsh-phone-vdisplay/`，保存私有凭据，删除临时配置，然后启动 Helper。成功输出 `Phone-local Helper ready.`。

启动脚本不会杀掉已有服务，不会清除手动接管状态，也不会替换正在运行的 Helper。已有凭据不同或本机端口被占用时会明确失败。

### 5. 在 DSHA 使用

按 DSHA 自身流程重新加载插件或重启其核心，新建会话后说：

> 用虚拟副屏打开设置，查看信息，完成后关闭副屏。

十个工具：`vd_status`、`vd_start`、`vd_orientation`、`vd_screenshot`、`vd_tap`、`vd_swipe`、`vd_type`、`vd_key`、`vd_preview`、`vd_stop`。

截图带实际尺寸及画面 revision；横竖屏变化后重新截图定位。默认竖屏 720×1280、横屏 1280×720。`vd_type` 会使用手机共享剪贴板。

## 副屏失效后怎样恢复

### 只是调用了 vd_stop

这会关闭虚拟屏及其中页面，Helper 仍在后台。下次直接 `vd_start`，无需重新安装。15 分钟无操作也会释放虚拟屏。

### 手机重启、清理后台或 Helper 退出

1. 自行恢复权限通道：例如重新启动 Shizuku、确认终端授权；无线调试是否需要重新开启/配对，按设备状态处理。
2. 在有权限的 Android 终端执行：

   ```sh
   sh /data/local/tmp/dsh-phone-vdisplay/start.sh
   ```

3. 回到 DSHA 查询 `vd_status`，确认正常后再开始任务。

日常重新拉起无需运行 `configure.mjs`、无需重新生成 token。若系统连运行文件也删除了，重新按完整安装流程部署。文件还在但启动失败时，用户可以在 Android shell 本地查看 `service.log`；日志可能含应用状态，分享前自行检查。

本项目不提供开机自启或保证后台永久存活，也不自动设置系统权限、电池策略或 root 模块。用户可按自己的手机环境解决保活，Shizuku/无线调试失效的处理参见对应工具官方说明。

### 手动接管或凭据不匹配

- `MANUAL_CONTROL`：在副屏预览页明确“交还机器人”。不要通过重启、删除标记或换主屏绕过接管。
- 凭据不匹配：保留原配置并恢复匹配关系，不要反复生成新 token。原配置已丢失时，需要用户先结束任务，再自行清理旧安装并重新配对安装。
- 端口占用或启动锁异常：先确认是否已有 Helper 正在启动/运行。启动脚本不会强制杀进程；确认为异常残留时由用户自行处理，或重启手机后重试。

## 预览和接管

告诉 DSHA“打开副屏预览”，它会在手机主屏浏览器打开本机一次性链接。视频目标 30fps，实际速度取决于设备、编码器和浏览器；失败时可切换 JPEG 截图。

点击“手动接管”后，机器人操作由服务端拒绝。关掉浏览器不会自动交还；需要明确点“交还机器人”。预览链接 5 分钟内一次有效，不是永久书签。截图工具一次只返回一张图，不能当成模型连续观看视频。

## 从 0.5.0 升级

1. 保存副屏里的工作、交还控制并关闭副屏。
2. 在 DSHA 使用本版 `configure.mjs` 导出已有凭据，并导入 0.5.1 插件。
3. 手机重启使旧 Helper 退出；自行恢复 Shizuku/本地 ADB/root 通道后，执行本版 `install-phone.sh`。

0.5.1 不会读取旧电脑恢复配置，不会请求旧网关。旧配置可由用户自行移除。本版不包含其他分支的 0.6.0 异步手势接口；不要把 0.6.0 功能说明当成本版 API。

## 源码与开发

```text
phone-plugin/   DSHA 插件，运行时只请求手机回环地址
src/            Android Helper 和本地健康检查入口
ui/             预览网页
scripts/        手机安装/启动/配置，以及开发者构建工具
vendor/         随包提供的 scrcpy 运行库及其参考源码
build/          预编译 Helper；其他编译中间文件不提交
dist/           手机 ZIP、DSHA TGZ 和校验值
tests/          客户端离线测试
```

普通用户无需编译。开发者需要 Node.js 20+、npm 和 JDK 17+（`JAVA_HOME` 或 Java 工具在 PATH）。编译依赖仅从官方地址下载到本仓库：

```sh
node scripts/download-build-tools.mjs
node scripts/build.mjs
node --test tests/*.test.mjs
node scripts/package-release.mjs
```

这些是开发工具，不是用户手机使用时要部署的电脑服务。调试时可用 `VDISPLAY_PORT` 指定 Helper 本机测试端口；生产插件固定请求 3096。测试安装可使用 `VDISPLAY_DIR=/data/local/tmp/dsh-phone-vdisplay-test-名称`，不会改变正式安装目录。

## 许可证

[Apache-2.0](LICENSE)。运行库来自 [scrcpy v4.1](https://github.com/Genymobile/scrcpy/tree/v4.1)，其许可见 [THIRD-PARTY-LICENSE.txt](THIRD-PARTY-LICENSE.txt)。包内不包含任何用户凭据、设备身份或私人服务地址。
