# 0.5.1 验证记录

2026-09-27，手机本地独立版。

## 已通过

- Java UTF-8 编译、D8 编译和 DEX 结构、Adler32、SHA1、版本检查。
- `node --test tests/*.test.mjs`：6 项测试。覆盖本机单次请求、旧远程配置不生效、手动接管不重试、取消请求、截图元数据、凭据错误，以及配置生成和复用。
- 红魔 NX789J / Android 16：独立测试目录及本机 3097 端口执行完整手机 ZIP 安装，私有凭据写入、临时配置删除、Helper 启动成功。
- 创建副屏、应用启动、JPEG 截图和触摸。
- 重复运行启动脚本保留活动副屏及手动接管；安装脚本拒绝覆盖正在运行的 Helper。
- 结束测试 Helper 后，重新运行启动脚本可恢复，原凭据仍有效。错误凭据返回 401。
- 原本机 3096 服务保持原版本，测试不覆盖原安装。

开发测试通过 ADB 在手机执行同一份 Android shell 脚本；ADB 是测试驱动通道，不是发行版的运行依赖。本轮未完整重走 Shizuku 的图形化配对/授权流程，不声称所有 ROM 或所有终端应用兼容。使用者必须自行获得可用的 Android shell 权限。

## 复现

普通使用流程见 README。开发者可先运行上述 Node 测试和构建，再在空闲测试手机使用完整 ZIP 验证。隔离测试可设置：

```sh
VDISPLAY_PORT=3097 VDISPLAY_DIR=/data/local/tmp/dsh-phone-vdisplay-test-validation sh /sdcard/Download/dsh-vdisplay-0.5.1/install-phone.sh
VDISPLAY_PORT=3097 sh /data/local/tmp/dsh-phone-vdisplay-test-validation/start.sh
```

需要先用配置脚本生成临时交接文件。测试端口仅用于开发，DSHA 插件始终连接生产端口 3096。

0.5.1 保留原有同步手势与视频实现；本次主要验证独立安装和恢复，不复用其他分支 0.6.0 的性能数据，也不承诺跨设备的 30fps 或固定触摸延迟。
