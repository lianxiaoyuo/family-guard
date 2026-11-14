# FamilyGuard V6.0.1-amap-rc1 — 家庭安全定位（完整源码 + 云服务器）

**Android 端：一个 APK，家长/孩子分别选择角色。Release 正式签名由本机 keystore 完成。** 家长手机和孩子手机不需要在同一网络；服务端运行于公网云服务器，必须经 HTTPS 访问。

## 从这里开始

请阅读 [V6.0.1-amap 完整交付与部署说明](README_V6_最终交付与部署说明.md)。

- `android/`：Android Studio 工程（Java 17 / Android SDK 35）；GNSS+系统融合+可选高德，SOS、带通知前台定位、加密断网轨迹、父母地图/历史轨迹/围栏/媒体、孩子端主动录音录像拍照
- `server/`：Go 服务端完整源码、Linux/Windows 构建程序、测试、Nginx 示例、Dockerfile
- `deploy/`：Ubuntu systemd 更新/回滚脚本、Windows SSH 部署脚本、Docker Compose 全新部署方案
- `tools/`：Java 静态语法校验工具
- `.github/workflows/`：Android APK CI 和 Go 服务端回归测试 CI
- `docs/`：V1–V5.3 的历史报告及部署文档（历史内容请以新版 README 为准）

## Release 正式签名

首次正式构建前，在 `android/` 执行 `GENERATE_RELEASE_SHA1_WINDOWS.ps1`。脚本在本机生成 `keystore/familyguard-release.jks` 与 `keystore.properties`，输出 Release SHA1；将其登记到高德控制台发布版 SHA1 栏。后续正式版本必须复用同一 keystore。详见 `docs/V6.0.1.2_Release正式签名与高德SHA1配置指南.md`。

## 不可省略的配置

1. 正式服务器 HTTPS 域名 + Nginx；不要直接公网开放 Go 8088。
2. `FG_SETUP_TOKEN` 首次部署密钥（仅由服务器管理员安全保存和使用），不要上传 Git。
3. 启用高德融合定位需自己的 Android Key，并匹配 App 包名与签名 SHA1，用户明确同意高德 SDK 隐私条款；不配置 Key 时自动使用原生定位。
4. 第一次安装两部手机需正确授予位置、前台服务/通知、必要的后台位置权限。

**真实验收限制**：此压缩包已包含完整源码和服务端可执行文件，但没有现场打包的 Android APK；真实安装、设备定位精度、Android 后台保活与公网 HTTPS 部署必须在你自己的设备上验证。录音录像采集是孩子端可见授权流程，不包含静默远程开启摄像头/麦克风。
