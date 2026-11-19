# FamilyGuard V6.0.1.2 固定 HTTPS 地址与首次配对体验补丁

## 改动
- App 内置服务器地址 `https://familyguard.lianmulan.top`，不向用户展示、索取服务器 URL。
- 启动时先选择“我是家长”或“我是孩子”，两种流程分别显示。
- 家长首次创建需要输入安全初始化密钥；可用单独恢复密钥恢复家长账号；孩子只输入 8 位配对码和昵称。
- 家长/孩子主界面不再提供修改服务器地址按钮；App 网络请求一律使用固定 HTTPS 地址。
- 不修改 Go 服务端、Nginx、TLS、DNS、权限系统，不把初始化密钥写入 APK。

## 用法
1. 下载 ZIP 并**全部解压**到任意本地文件夹。
2. 打开 PowerShell，在解压出的补丁文件夹运行：
   `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\APPLY_WINDOWS.ps1 -Project 'C:\Users\74787\Downloads\d\FamilyGuard_V6_Complete' -BuildApk`
3. 仅对与本次 V6.0.1.2 基线一致的三个 Java 文件应用；原文件自动备份。
4. 构建成功后 APK 在 `android\app\build\outputs\apk\debug\app-debug.apk`。

## 注意
- 这是仅包含 Java 源码的升级包，不是可直接安装的 APK。本环境无 Android SDK，不声称已通过 Gradle 构建或真机测试。
- 高德地图需要本地已有正确的 Android Key 和与签名 SHA-1 匹配的配置；Debug 包仅供测试，非生产发布包。
- 如果项目本地源文件与基线不同，脚本会停止以避免覆盖更改；请针对本地差异合并。
- Chrome 浏览器显示 ERR_CONNECTION_CLOSED 与 Android App 的 UI 无关，需独立检查代理/VPN及 TLS；不要因浏览器错误擅自修改医疗 Nginx。
