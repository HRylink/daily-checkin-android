# GitHub 发布说明（仅手机端）

目标仓库：<https://github.com/HRylink/daily-checkin-android>。以下网页流程也可在终端未授权时作为备用上传方式。

## 创建公开仓库

1. 解压 `每日签到-手机端-GitHub公开包-v1.0.zip`，打开其中的 `daily-checkin-android/`。
2. 在已登录的浏览器打开 <https://github.com/new>。
3. 仓库名填写 `daily-checkin-android`；描述可填「Android 中转站签到助手：网址自动识别、通用网站配置、一键领取积分或额度」。目标仓库已存在时，直接进入下节上传，不重复创建。
4. 选择 **Public**。不要勾选自动创建 README、.gitignore 或 License；包中已提供前两者，本发布未擅自指定项目许可证。
5. 点击 **Create repository**。

## 上传文件

1. 空仓库点 **uploading an existing file**，非空仓库点 **Add file → Upload files**。
2. 将解压目录**里面的文件和子目录**拖入上传区，保留 `src/`、`tests/`、`res/`、`tools/`、`docs/` 结构；不要再套一层外目录。
3. 文件管理器按 `Ctrl+H` 显示隐藏文件，确认 `.gitignore` 也上传。
4. 确认根目录有 `README.md`、`AndroidManifest.xml`、`build.sh`、`test.sh`、`使用说明.txt`、`签到.apk`、`SHA256SUMS.txt`。
5. 提交说明填写 `Publish Android app v1.0`，点 **Commit changes**。

**GitHub 不会自动解压 ZIP 为源码，必须先在本机解压。不要直接全选原工作目录上传，原目录含签名私钥、内部日志和备份。**

网页上传可能丢失脚本可执行位，下载后可用 `bash test.sh` 和 `bash build.sh`。

## 发布 APK 下载页

1. 打开仓库右侧 **Releases → Create a new release**。
2. 创建 tag `v1.0`，目标选择刚上传的分支（通常是 `main`）。
3. 标题填写 `每日签到 v1.0`，将 `docs/RELEASE_NOTES_v1.0.md` 内容粘贴到说明。
4. 附件上传同一份 `签到.apk` 和 `SHA256SUMS.txt`。
5. 点击 **Publish release**，不勾选 Pre-release。

## 公开后检查

- 仓库显示 **Public**，未登录也能访问。
- 根目录 README 正常显示，`src/com/velpro/checkin/` 含 Java 源码。
- 可下载 APK，Release 显示 `v1.0`，SHA-256 与 `SHA256SUMS.txt` 一致。
- 仓库中没有 `debug.keystore`、其他私钥、`build/`、`verification/`、内部参考文件或电脑端签到代码。
- 手机显示版本 **1.0**，内部 `versionCode=11`。

本地打包不等于已上传 GitHub；只有网页提交和 Publish release 完成后才算发布成功。
