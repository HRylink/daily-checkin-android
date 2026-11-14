# 从源码构建

这些命令用于在本机复现手机端 APK，不需要安装到手机的 Python 或电脑端签到脚本。公开发布包应包含根目录 `签到.apk`，但**不应包含签名私钥、构建产物、验证日志或本站协议参考文件**；本目录的 `.gitignore` 已将这些文件排除。

## 环境

- JDK 17 或更高：随包的 `tools/ecj.jar` 是 Java 17 字节码，运行它需要 Java 17+；Android build-tools 与 JVM 测试也需要较新的 JDK。应用 Java 源码按 Java 8 语言级别编译（脚本传 `-1.8`），JVM 测试按 Java 11 编译；构建不需要 Gradle。已验证环境使用 OpenJDK 25。
- Python 3：**仅用于静态接线和 UI 契约测试**；本地 HTTP 夹具由 Java 测试实现。APK 构建不需要 Python、Pillow 或 Node.js。启动器 PNG 已预生成在 `res/mipmap-*/`，`tools/gen_icon.py` 只是一次性生成工具。
- 下列脚本面向 Linux/Bash 环境；Windows 可使用 WSL。网页上传可能丢失文件可执行位，下面使用 `bash` 显式启动脚本。
- Android SDK Platform 34 与 Build Tools 34.0.0，需包含 `android.jar`、`core-for-system-modules.jar`、`aapt2`、`d8`、`zipalign`、`apksigner`。
- 环境变量 `ANDROID_SDK_ROOT` 指向 SDK 根目录；未设置时 `build.sh` 回退到 `$HOME/android-sdk`。脚本使用 `$ANDROID_SDK_ROOT/build-tools/34.0.0` 和 `$ANDROID_SDK_ROOT/platforms/android-34/`。
- 仓库自带 `tools/ecj.jar` 与 `tools/json.jar`：前者用于编译，后者只为纯 Java 测试提供 `org.json`；Android 平台自身提供运行时 `org.json`，不向 APK 打入第三方依赖。
- Bash、`find`、`zip`、`keytool`（来自 JDK）。

## 测试

在源码根目录运行：

```bash
bash test.sh
```

脚本会编译并运行 12 个手机端套件，覆盖网络协议、网站账号管理、通用接口、余额、登录、可靠性、任务运行和历史，以及 `tests/android_contract_test.py`、`tests/ui_contract_test.py`。它们使用 JVM、本地 HTTP 夹具与静态源码检查，不连接真实站点，也不是 Android 设备端测试。测试输出可能沿用内部协议模块的类名，不表示只适用于某一个中转站。

`tests/desktop_logout_test.py` 属于电脑端流程，已从 `test.sh` 移除；公开发布也不包含该测试（`.gitignore` 将其排除）。不要把它重新接入手机端回归。

## 构建

```bash
bash build.sh
```

脚本执行：Java 8 编译 → `d8` 转 dex → `aapt2` 编译/链接已存在的 `res/` → 打包 `classes.dex` → `zipalign` → `apksigner` v2/v3 签名 → 校验对齐与签名。输出根目录 `签到.apk`，适用 minSdk 26 / targetSdk 34。

首次运行且当前目录没有 `debug.keystore` 时，`build.sh` 会用 `keytool` 生成自己的 RSA 调试签名密钥（别名 `androiddebugkey`，口令 `android`）。**私钥不公开，也不会随发布包上传。**`.gitignore` 排除 `debug.keystore`、`*.keystore`、`*.jks`、`*.pem`、`*.key`、`*.apk.idsig`、`build/`、`verification/` 等文件。

自行生成的调试密钥与官方 APK 的签名证书不同，Android 会因签名不匹配拒绝直接覆盖安装官方版本；卸载会删除应用私有账号数据与登录态。若需要保留官方包的数据，请安装发布者用同一私钥签名的官方 APK，不要替换签名密钥。

## 发布边界

- 随包的 ECJ 与 `org.json` 测试 jar 内含各自来源/许可声明，汇总见 [第三方组件说明](THIRD_PARTY.md)；Android SDK 与 JDK 由开发者按各自条款自行安装，本目录不替第三方指定许可。
- 本目录没有 LICENSE：公开源码和 APK 不代表已授予开源许可证。
- 公开包不包含 `docs/plans/`、`docs/reference-*.mjs`、电脑端代码或验证日志；如从原工作目录发布，请勿上传这些内部文件。
