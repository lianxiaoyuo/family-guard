# FamilyGuard V6.0.1-amap-rc1 — 家庭安全定位全量源码与公网部署包

发布日期：2026-11-14。该版本是 **可进行部署和真机测试的候选工程，不是已通过 Android 实机验收的生产正式版**。

## 1. 本次保留了什么

以原有 FamilyGuard V5.3 完整代码为基础升级，保留 Android 原生 Java 单 APK（孩子端、家长端按角色使用），Go HTTP 单体服务端、现有家庭注册与配对协议、身份 Token 与恢复密钥、既有服务器数据格式。**未擅自改用全新 Spring Boot/PostgreSQL 后端**，避免破坏旧版已验证的 API/数据。未来如要迁移 Spring Boot，应另行做等价回归和数据迁移。

功能范围：

- 孩子端：GNSS（GPS、北斗等，依手机芯片）＋系统 Fused/Network ＋ 可选高德融合定位 SDK；持续共享有可见通知、随时可停止；离线缓存后联网补传。
- 家长端：1 秒尝试刷新最新位置；使用高德 Android 3D 地图显示孩子标记、精度圈与最近 1/6/24 小时轨迹。
- 电子围栏：服务器上设置圆形围栏（WGS84 中心，80–2000 米）；进出双点确认与迟滞；新鲜定位才报警。
- SOS：本机持久化、失败重试、服务端 requestId 去重、离线延迟标识；可选 ntfy 主动推送。
- 照片/录音/视频：家长申请→孩子手机显式确认→采集→上传；另外孩子端可主动拍照、录 30 秒音/视频发送给家长；点击孩子端可见的「SOS + 自动启动可见录音」按钮时，会先本地保存 SOS，再尝试在线申请并启动 30 秒录音。**不会绕过系统权限、锁屏后秘密打开麦克风/摄像头**。
- 安全：设备绑定、分角色令牌、文件鉴权、后端服务默认不直接公网开放、HTTPS、媒体过期清理。

## 2. V6.0 的关键修复

1. **历史轨迹终于真正持久化**：V5.3 的孩子端上传主要走 `/v1/position`（只更新最新位置），导致 `/v1/history` 长期为空。现在先发最新位置，再通过 `/v1/points` 批量上传坐标历史，二者互不阻塞精度展示；仅收到服务器成功应答才删除缓存。
2. **断网不再覆盖旧坐标**：`pending_points.db` 从 schema v2 升为 v3（平滑升级，保留已有加密数据），最多缓存最新 1200 个加密定位点，保留最长 6 天；优先传最新点，再传历史队列。断网过久超过容量的较旧点可能丢失，绝不伪称轨迹连续。
3. **防止批量补传误触发围栏**：`/v1/points` 加入与实时端点统一的来源/精度/时间校验；只对相对于最近已处理位置更新鲜、且采集时间足够新的点进行电子围栏评估。重复上传同一 ID 不重复添加历史/重复报警。
4. **家长端轨迹展示**：增加 1/6/24 小时按钮及绿色轨迹线；超过 10 分钟的轨迹空档分段显示，不用连线虚构运动路线。
5. **主动媒体采集**：新增 `/v1/media/self` 孩子端授权创建媒体任务，仍然必须在可见界面手动启动拍照/录音/录像。保护端点仅允许 child Token 创建，父母不能假冒孩子端采集。
6. **公网上线部署选项**：增加 `deploy/compose.yaml`、`server/Dockerfile`、`.env.example`；兼容此前 systemd 部署脚本；不修改现有云服务器或 Nginx 配置。
7. **新增回归测试与 CI**：加入历史轨迹+幂等+围栏+媒体权限测试，以及服务端 GitHub Actions 测试。

## 3. Android Studio 打包（Windows）

1. 使用 Android Studio 打开 **`android` 文件夹**（不是外层）。Gradle JDK 使用 **17**，Android SDK Platform 安装 **35**。
2. 对于高德融合定位，需在高德开放平台申请 Android Key，**包名是 `com.familyguard.app`**，SHA-1 需与实际 APK 签名证书匹配。本交付包已在 `android/amap-key.properties` 写入你提供的 Android Key（文件已加入 `.gitignore`，不要提交 Git）。包名为 `com.familyguard.app`，Debug SHA1 按你提供的值登记；Release 签名尚未创建。运行 `android/VERIFY_AMAP_CONFIG_WINDOWS.ps1` 检查注入，再执行 `gradlew.bat :app:assembleDebug`。若移除 Key，孩子端仍可用原生 GNSS，但高德融合定位和家长端内嵌高德地图不可用。
3. 对高德 SDK 的位置与设备数据处理，用户在孩子端须主动确认隐私授权；App 对 `updatePrivacyShow`、`updatePrivacyAgree` 的调用在 SDK 初始化前进行。后台位置权限需在系统设置中授予“始终允许”，同时允许前台定位通知及系统必要的后台运行策略。
4. 运行 `gradlew.bat :app:assembleDebug`，APK 预计生成于 `android/app/build/outputs/apk/debug/app-debug.apk`。安装同一 APK 到孩子 Redmi K90 和家长小米10，选择对应角色。
5. **不包含签名私钥**；本次按你的要求在本地配置文件中写入 Android Key，`android/amap-key.properties` 已加入 `.gitignore`，不要将其单独提交到公开仓库。Android Key 会被打包进 APK，必须在高德控制台绑定正确的包名与 SHA1。生产版请自行使用专用 keystore 签名，并把 Release SHA1 补充到同一个 Android Key 后再发布；正式版必须使用公网 HTTPS 域名。

## 4. 高德地图与定位接入

当前家长端和孩子端均为同一个 Android APK 的不同角色，因此同一个“Android平台SDK”Key 同时用于家长端高德地图和孩子端高德融合定位；本工程没有浏览器地图页面，不需要 JS API Key/securityJsCode。详细控制台配置、SHA1、隐私初始化和真机验收见 `docs/高德Android地图与融合定位接入说明.md`。

## 5. 公网云服务器部署（Ubuntu）

已有 V5.3 系统使用者优先沿用 `deploy/install_server_ubuntu.sh` 与现有 systemd/Nginx，**先备份 `/var/lib/familyguard`**。V6.0 保留原 API、家庭数据格式和默认监听端口 `127.0.0.1:8088`。不要同时运行 systemd 与 Docker 两套服务。

全新 Docker 方案（需要 Docker Engine + Compose v2，下面在工程根目录执行）：

```bash
cd deploy
cp .env.example .env
# 生成64位随机十六进制部署密钥，写入 .env 的 FG_SETUP_TOKEN
# 例如：openssl rand -hex 32
chmod 600 .env
docker compose -f compose.yaml build
docker compose -f compose.yaml up -d
docker compose -f compose.yaml ps
curl -fsS http://127.0.0.1:8088/healthz
```

必须在 `.env` 中先将占位密钥改为随机强密钥。仅在家长首次创建家庭时使用，不需要发给聊天机器人。容器端口只绑定宿主 `127.0.0.1:8088`。用 `server/nginx-familyguard.example.conf` 作为**独立虚拟主机示例**配置公网 HTTPS 域名，证书由服务器自己申请。只开放公网 443，**不要开放 8088 或数据库端口**。已有网站请合并而不是覆盖 Nginx。中国大陆实际公网部署还要确认适用的网络接入与域名备案要求。

创建家庭 → 保存一次性恢复密钥 → 获取 8 位配对码 → 孩子端入组 → 孩子端同意共享 → 校验移动网络下的位置/轨迹和围栏。父母收到 SOS 需实际在锁屏手机上验证通知到达，ntfy 外部服务不保证中国大陆网络稳定。

### 数据备份/迁移

- systemd 旧方案：备份 `/var/lib/familyguard`，保留目录权限与全部家庭文件。
- Docker 新方案：数据存在命名卷 `familyguard_data`，正式升级前备份该卷；**原来的 systemd 数据不会自动进入 Docker 卷**。请不要在旧数据库尚未迁移时切换业务入口。
- 生产发布需先回滚演练：保存旧二进制和数据，确认健康检查及角色配对；出现异常先恢复原程序，再处理可能的新格式数据。

## 6. 验证与未验证边界

本环境已执行：服务端 `go test`、race 测试、Linux/Windows Go 构建、全部 Android Java 源文件语法解析、Manifest XML 解析，以及 AMap Key 文件/包名/Manifest 占位符/SDK 调用链静态检查。由于当前环境没有 Android SDK、Gradle wrapper distribution 和 AMap Maven 依赖缓存，也无法操作你的 Redmi K90/小米10或高德控制台，**不能声称 Android APK 已编译、AMap Key 已完成在线鉴权、地图瓦片已加载或高德融合定位已在真机返回有效坐标**。

系统限制：没有厂商/系统授权时，App 被系统强制停止、用户撤销后台位置权限、手机关机/断网均可能终止实时共享。高德 1 秒间隔是“请求间隔”，不是每秒得到准确坐标。录音录像、照片采集必须设备侧可见授权，不能远程隐蔽开启；音视频断网离线续传未实现，SOS+录音的媒体会话若无法联网创建，则仍仅将 SOS 排队重试，不能离线自动上传媒体，失败要重新拍摄/录制。该 App 是家庭安全辅助工具，不能替代报警电话。

## 7. 下一轮必要真机/上线验收

- Android Studio Build APK 成功，检查权限弹窗、Key 的包名/SHA1 和高德融合 SDK 真正工作；无 Key 时原生 GNSS 回退正常。
- 两部手机异地、4G/5G 网络位置共享；孩子锁屏 8–24 小时、断网/重启后续传、关机后家长显示过期位置。
- 室内外定位精度实测，高德/系统坐标均正确，GCJ02/WGS84 围栏转换不误报。
- 家长 1/6/24 小时路线可回放；300 条以上历史坐标不阻断最新位置。
- 围栏双点确认、SOS、在线/离线告警；家长锁屏状态检查推送。
- 拍照、录音、录像各执行一次，被拒绝/取消时无媒体上传；检查用户停止共享后状态变更。
- 完整服务端异常、磁盘满、网络抖动、滚动升级/回滚、媒体到期清理测试。

## 8. 第三方文档

- 高德 Android SDK 集成：https://developer.amap.com/api/android-sdk/guide/create-project/android-studio-create-project
- 高德隐私合规：https://developer.amap.com/api/compliance-center/check-and-reference/sdkhgsy
- Android 后台前台服务限制：https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
