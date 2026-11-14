# FamilyGuard V6 — 高德 Android 地图与融合定位接入

## 当前工程的接入方式

V6 当前是一个 Android 原生 APK，家长端和孩子端是同一个 APK 的两种角色。家长端地图现在已从 MapLibre/OpenFreeMap 替换为高德 Android 3D Map SDK；孩子端继续使用高德 Android Location SDK + Android 原生 GNSS。两者由同一个 `com.amap.api:3dmap-location-search` Android SDK 依赖提供，因此**本工程不需要 JS API Key，也不需要 `securityJsCode`**。只有另行开发浏览器网页地图时，才需要单独申请 Web端(JS API) Key 与安全密钥。

## 已写入本地配置

- Android Key：已写入 `android/local.properties`，同时保留 `android/amap-key.properties` 作为回退配置（两者均已加入 `.gitignore`，不要提交到公开仓库）。
- Gradle 优先读取 `local.properties` 的 `AMAP_KEY`，其次读取 `amap-key.properties`；Android Studio 可在 `local.properties` 中追加本机 `sdk.dir`。
- applicationId / Package：`com.familyguard.app`。
- Debug SHA1（按用户提供值登记）：`B6:3B:7C:2E:67:BE:D6:59:D4:72:CC:5E:1D:C4:7F:20:43:5A:0B:63`。该值必须填入控制台的“调试版安全码 SHA1”，不能填入发布版字段。
- Release SHA1：用户尚未创建正式签名文件；控制台截图将发布版 SHA1 标记为必填，因此需先在用户电脑运行 `android\GENERATE_RELEASE_SHA1_WINDOWS.ps1`，把输出填入“发布版安全码 SHA1”。只需回传 SHA1，不要发送 JKS 文件或密码。
- Release 签名：尚未创建，因此目前只能按 Debug 签名测试。创建正式签名后，必须将 Release SHA1 追加到高德控制台该 Android Key 的安全码配置中。

## 高德控制台应配置

创建/编辑一个应用，添加 Key，服务平台选择“Android平台SDK”，Package 填 `com.familyguard.app`，调试版 SHA1 填 `B6:3B:7C:2E:67:BE:D6:59:D4:72:CC:5E:1D:C4:7F:20:43:5A:0B:63`（在控制台不要填写前缀 `SHA1:`）。控制台截图将“发布版安全码 SHA1”标为必填，不能把 Debug SHA1 填入该字段。先生成 Release 签名文件并填写其 SHA1；用户提供的 SHA1 填入“调试版安全码 SHA1”。请确认该 Key 的服务平台确实是“Android平台SDK”，而不是 Web端(JS API) 或 Web服务。高德官方说明 Android Key 需绑定 Package、Debug SHA1 和 Release SHA1，签名不同会导致鉴权失败。

官方文档：
- Android Key 申请：https://developer.amap.com/api/android-location-sdk/guide/create-project/get-key/
- Android 地图 SDK Gradle 集成：https://developer.amap.com/api/maps-sdk-for-android/guide/create-project/android-studio-create-project
- Android 定位 SDK 隐私合规：https://developer.amap.com/api/location-sdk-for-android/guide/create-project/dev-attention

## 编译与检查（Windows）

1. Android Studio 打开 `android` 目录，确认 Gradle JDK 17、Android SDK Platform 35。
2. `android/local.properties` 已预填用户提供的 Android Key；Android Studio 可在同一文件追加本机 `sdk.dir`。运行 `android\VERIFY_AMAP_CONFIG_WINDOWS.ps1`，它会检查 `local.properties` / `amap-key.properties`、Manifest 占位符、applicationId，并调用 Gradle `verifyAmapConfiguration` 任务。不会在控制台打印完整 Key。
3. 生成正式签名 SHA1：运行 `android\GENERATE_RELEASE_SHA1_WINDOWS.ps1`，将输出填入高德控制台“发布版安全码 SHA1”；用户提供的 SHA1 填入“调试版安全码 SHA1”。
3. 运行 `android\gradlew.bat :app:assembleDebug`。
4. 将 Debug APK 安装到 Redmi K90 与小米10。家长角色打开地图，孩子角色在同意高德隐私政策后开启共享。
5. 使用 `adb logcat -s FamilyGuard FamilyGuard.AMap AMap` 查看定位错误码；家长端应看到 `AMap map tiles loaded` 日志，孩子端应看到 `AMap location SDK returned a valid fix` 日志，并在位置来源状态中出现“高德融合定位”。

## 隐私与安全

高德 SDK 的 `updatePrivacyShow` / `updatePrivacyAgree` 必须在创建 `MapView` 或 `AMapLocationClient` 前调用。应用会在家长地图或孩子端融合定位首次启用前展示授权说明；拒绝高德授权时，原生 GNSS 定位仍可用，但高德地图/高德融合定位不可用。Android Key 会被打包进 APK，属于客户端 Key，不是服务端密码；请在高德控制台绑定正确的包名与 SHA1，并监控调用配额。不要把任何 keystore 私钥或密码交给他人。

## 验收边界

代码已完成 SDK Key 注入链路和地图/定位初始化调用修改：Manifest metadata 使用 `${AMAP_KEY}` 占位符；Gradle 从 `amap-key.properties` 读取 Key 并写入 Manifest 与 `BuildConfig.AMAP_KEY`；父端 `MapView` 与孩子端 `AMapLocationClient` 均在隐私合规接口调用后创建。当前执行环境没有 Android SDK、Gradle wrapper distribution 和网络访问，不能在此环境完成 Android APK 编译，也不能验证高德控制台 Key 是否已按 Package/SHA1 正确绑定或在 Redmi K90 上成功鉴权。`verifyAmapConfiguration` 是配置静态检查，不等于真实 SDK 网络鉴权成功。最终必须在 Android Studio 构建并在两台真机上验收。
