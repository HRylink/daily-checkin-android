# 第三方组件与来源说明

本项目自身的开源许可证未在本发布中指定。**公开源码不等于自动授予他人复制、修改或再分发的许可**；如准备为整个项目选择许可证，请先确认自己有权对全部代码和文档作出授权。

本文件只记录随包提供或构建使用的第三方组件来源，供核对；不替代正式法律意见。

## 随源码包附带的工具

- `tools/ecj.jar`：Eclipse Compiler for Java，JAR 内 `about.html` 声明默认适用 **Eclipse Public License 2.0**。元信息版本为 `3.39.0.v20240820-0604`；上游项目：<https://github.com/eclipse-jdt/eclipse.jdt.core>。JAR 内还有 Eclipse.org 和源版本信息。
- `tools/json.jar`：`org.json:json`，元信息版本 `20240303`；JAR 内 Maven 信息声明 **Public Domain**，Manifest 指向项目 LICENSE，上游：<https://github.com/stleary/JSON-java>。

上述描述依据本地 JAR 的 MANIFEST、Maven 元信息和 `about.html`；未验证下载渠道或官方哈希，也未独立核验上游许可正文。JAR 本身可能包含更多第三方文件。发布时保留原件及其中声明，不要擅自删除许可信息。

## 运行/构建依赖（不包含在发布包内）

- Android SDK platform `android-34` 和 build-tools `34.0.0`（`aapt2`、`d8`、`zipalign`、`apksigner`）：由开发者按 Android SDK 条款自行安装。
- JDK 17+ 与 `keytool`：由开发者自行安装；`build.sh` 使用 Java 8 源码级别，但随包的 ECJ 编译器以 Java 17 字节码提供。
- Python 3：仅静态契约测试需要；APK 构建不依赖 Python。
- Bash、zip、curl/wget 等常见系统工具。

## 图标字体

`tools/gen_icon.py` 一次性使用系统 Noto Sans CJK Bold 字体生成 PNG；普通构建只读取 `res/mipmap-*/ic_launcher.png`，不读取该字体或运行 Python。字体按 **SIL Open Font License 1.1** 提供，许可证原文可从 `/usr/share/licenses/google-noto-sans-cjk-fonts/LICENSE` 查看。生成的字标以位图形式发布，OFL 对字体本身及衍生字体的约束仍应注意。

生成脚本依赖 Pillow；Pillow 不需要安装即可构建 APK。

## 本项目与站点协议

应用代码与各站点的网络接口仅供个人学习和自有账号使用。源码中引用的站点名称、客户端标识与协议字段归各自权利人所有；本项目不声称与其存在关联或获得其授权。站点随时可能更改接口、验证方式或服务条款。

部分实现曾参考独立插件源代码；本发布不附带那些参考文件，也不对被排除代码的权属或再许可范围作保证。如需对整个项目开源授权，请先单独核对这些来源。
