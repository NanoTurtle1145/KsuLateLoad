# KsuLateLoad — 一键 KernelSU late-load（需临时 root + Shizuku）

Android 小工具：当设备上已有 **CVE-2026-43499 临时 root**（`/data/local/tmp/rms24/helper` root 守护进程运行中）时，
通过 **Shizuku** 获得 shell 权限，一键执行 `helper --late-load`，把 KernelSU 驱动以 late-load 模式加载进当前内核。

> ⚠️ 仅用于**自己拥有的设备**上的研究/自用。late-load 只在**当前这次开机**有效，重启后需要重新执行。

## 前置条件

1. 设备已用 CVE-2026-43499 流程提权，且 `/data/local/tmp/rms24/helper` 与 `ksud` 存在（helper 守护进程运行中）
2. 已安装并启动 **Shizuku**（ADB 模式即可）
3. 本应用已获 Shizuku 授权

## 使用

1. 安装 APK（`dist/KsuLateLoad-v1.2-release.apk`）
2. 打开 App → 首次会弹出 Shizuku 授权框 → 点 **始终允许**
3. 点 **检查状态**：应看到
   - `Shizuku shell 身份: uid=2000(shell)`
   - `helper / root: uid=0(root)`
4. 点 **Late-load KernelSU**：自动暂存 ksud → 执行 `helper --late-load` → 验证 `kernelsu` 模块与 SELinux 状态

成功标志：`cat /proc/modules | grep kernelsu` 出现 `kernelsu ... Live`。

## 说明

- 必须使用 **非 debuggable 的 release 签名包**：debug 包会被 Shizuku 自动授权而不弹框（也不会出现在管理器的授权列表中）
- 应用本身不包含 exploit 代码；exploit 在电脑侧通过 ADB 完成
- 依赖 Shizuku API（Apache-2.0），详见 `NOTICE`

## 构建

```bash
./gradlew assembleRelease
# 产物未签名：app/build/outputs/apk/release/app-release-unsigned.apk
# 用你的 keystore 签名（或 debug keystore）：
zipalign -f 4 app-release-unsigned.apk aligned.apk
apksigner sign --ks <keystore> --out KsuLateLoad.apk aligned.apk
```

环境：JDK 17+、Android SDK 36、AGP 9.3.1、Gradle 9.7。

## 版本

- v1.2 (2026-11-01)：修复 Shizuku 授权流程（主线程申请、release 包弹框）、修复启动崩溃、增加临时 root 前置检查
- v1.0 (2026-11-01)：首版

## License

MIT（见 `LICENSE`）；Shizuku API 为 Apache-2.0（见 `NOTICE`）。
