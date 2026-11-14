# FamilyGuard V5.2 — 两部手机异地守护（自有云服务器版）

**源码审查与服务端回归测试完成；Android APK 尚未在本执行环境完成实际编译/真机验收。**

本版明确保留 Android 原生 Java + Go 单体服务端：红米 K90（孩子端）通过其 4G/5G 网络连接你的云服务器，小米 10（家长端）无论身在何处均通过互联网查看。**两部手机不需要同一 Wi-Fi；Android 无需配置高德 Key。**

## 核心功能

- **位置**：GNSS/GPS/北斗 + 系统 Fused + 系统网络定位；约每秒请求定位，筛选新鲜可信坐标、过期与不合理跳点；孩子端上传最新坐标，家长端地图约每秒轮询最新位置。实际精度与刷新速度必须用红米 K90 实测；OpenFreeMap 底图在国内可能加载失败，文字经纬度仍可使用。
- **电子围栏**：服务器按 WGS84 统一比较（也可接收并换算 GCJ-02），两次可信定位 + 迟滞减少边界抖动，进出事件由云服务器记录。首次启用时记下初始状态；不承诺围栏自动识别一切危险。
- **SOS**：孩子端一键求助或点击持续定位通知的 SOS；**先加密持久保存，再异步上传；失败重试、服务端基于 requestId 去重；超过 7 天仍未送达的消息会作为过期事件处理**，以免把旧求助当作新紧急情况。只有服务端确认接收时才从本机待发送队列删除。
- **紧急通知**：若在云服务器配置 `FG_ALERT_URL`（HTTPS ntfy 私密主题），SOS 和围栏进出事件可由服务器主动通知家长的小米 10，无需家长持续打开 FamilyGuard。手机端必须安装、订阅、授权 ntfy，测试真实送达率。`ntfy.sh` 在国内不保证稳定访问，正式使用更推荐可正常到达的自托管通知服务或另接厂商推送。短信/电话急救应作为备用。
- **照片、30 秒录音/视频**：家长发送请求，孩子端接收清晰的系统通知、在可见界面确认并采集；经自己的服务器授权上传，家长凭账号查看，媒体过期后不可访问并执行定期清理。**不提供锁屏后台隐蔽远程启动摄像头或麦克风的功能。**

## V5.2 修复与增强

1. SOS 增加加密本地 FIFO 待发送队列，断网、超时和重启后可重试；服务端持久化 `requestId` 去重，避免恢复联网时重复触发多条报警，保留原始求助时间并标记延迟补传。
2. 修正电子围栏此前跳过 GCJ-02 坐标的行为，加入带边界迟滞的转换后比较；增加北京/上海/深圳地理坐标回归用例。
3. 修复 Nginx 配置 `client_max_body_size 128k` 与 25 MiB 媒体限制冲突，改为 `26m`；上传读写时限调为 180 秒。
4. 媒体大文件上传/下载不再长时间占用整个 Go 服务端家庭锁，避免慢速音视频阻塞最新位置和 SOS；解绑期间尚未提交的上传不会成功授权。
5. 为私人服务器提供 `FG_SINGLE_FAMILY=1`，防止全局 ntfy 主题错误接收其他家庭的事件；systemd 和 Windows 示例默认启用。服务器已有多个家庭时，需要先清理与迁移，不要直接开启全局推送。
6. 保留 V5.1 的地图、权限、加密缓存与家庭角色鉴权机制；修改 APK 版本号，保持包名以便覆盖安装。

## Android Studio 打包

1. 解压后 **只打开 `android/` 文件夹**，不要打开项目外层目录。Gradle JDK 选 **17**，安装 Android SDK Platform **35**。
2. 如 `gradle/wrapper/gradle-wrapper.jar` 缺失，先执行 `android/BOOTSTRAP_GRADLE_WINDOWS.ps1`（联网下载并校验），或者在 Windows 下运行 `android/BUILD_APK_WINDOWS.cmd`。Gradle 下载需要能访问官方仓库。
3. Android Studio 点击 **Build > Build APK(s)**，或终端运行 `gradlew.bat :app:assembleDebug`。APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。
4. 在红米 K90 和小米 10 上安装同一 APK。家长创建家庭（妥善保存恢复密钥），孩子输入配对码并主动开启定位共享。
5. **两台手机填同一个公网 HTTPS 服务端地址，例如 `https://guard.example.com`**。不能用 `localhost`、`192.168.x.x` 作为异地使用的服务端地址。

## 云服务器部署（Ubuntu / Linux x86_64）

**先备份旧版本数据。不要清空原有 `/var/lib/familyguard`。**

```bash
# 在服务器上由管理员执行（域名及证书请替换为你自己的）
sudo systemctl stop familyguard 2>/dev/null || true
sudo mkdir -p /opt/familyguard /var/lib/familyguard
# 如果已有数据：sudo tar -czf /root/familyguard-before-v52.tgz -C /var/lib familyguard
sudo install -m 755 server/familyguard-server /opt/familyguard/familyguard-server
# 新服务器首次安装：创建受限服务账号，授权数据目录
id familyguard >/dev/null 2>&1 || sudo useradd --system --no-create-home --shell /usr/sbin/nologin familyguard
sudo chown familyguard:familyguard /var/lib/familyguard
sudo chmod 700 /var/lib/familyguard
sudo install -m 644 server/familyguard.service.example /etc/systemd/system/familyguard.service
sudo systemctl daemon-reload
sudo systemctl enable --now familyguard
sudo systemctl status familyguard --no-pager
curl -f http://127.0.0.1:8088/healthz
```

将 `server/nginx-familyguard.example.conf` 的 **域名和 TLS 证书路径**替换为实际值，合并进现有 Nginx（不要覆盖其他站点的配置），执行 `sudo nginx -t && sudo systemctl reload nginx`。只让 Nginx 对公网开放 443；**不要公网暴露 Go 的 8088**。

通知设置：在 Linux 的 `/etc/familyguard/alerts.env`（文件权限 600）设置 `FG_ALERT_URL=https://你能稳定访问的ntfy服务/随机私密主题`，如有身份认证再设置 `FG_ALERT_TOKEN=...`。更新服务后 `sudo systemctl restart familyguard`。本包 systemd 中已启用 `FG_SINGLE_FAMILY=1`。**ntfy 网关已接收消息不等于家长手机实际收到**，必须实测锁屏通知。

> 如果原服务器已存在多家庭，请勿直接使用一个通知地址转发全部家庭。本工程部署示例按你要求仅限一个家庭、两部手机使用。

## 正式使用前必须通过的验收

- Android Studio 完整 `assembleDebug` **成功**；两部手机完成配对，均使用公网 HTTPS，Wi-Fi 关闭后仅凭各自蜂窝网络定位与查看仍正常。
- K90 在室外、教学楼、商场测实际误差与更新时间；锁屏 8–24 小时、弱网、切换 4G/5G、断网恢复和开机恢复后持续显示最新位置。
- 连续触发 SOS，模拟断网/恢复、服务器重启；家长手机 App 关闭/锁屏时验证 ntfy 提醒真实送达，以及误报、迟报辨识。
- 进出电子围栏、应用重启、长期离线以及 WGS84/GCJ-02 坐标场景验证；不将电子围栏当作唯一救援措施。
- 在真实 HTTPS + Nginx 环境测试照片、30 秒录音、30 秒录像上传和播放，检查 24 小时访问过期与数据清理。

## 验证边界

本执行环境已完成 Go 回归、竞态、静态检查、Windows/Linux 交叉编译、HTTP 关键链路以及纯 Java 定位测试、Java 全文件语法解析。**没有 Android SDK 与在线 Gradle 依赖，因此无法在此验证完整 APK 编译，更无法替代两部真实手机的测试。** 这是一份带有自动化回归检查的完整源码交付，**不是已通过真机生产验收的成品**。

---

## V5.3 发布前加固（2026-11-11）

- **注意：本版没有连接或更改你的任何真实云服务器，也未完成 Android 真机验收。** 这是部署前候选版本。
- 服务器 `FG_SINGLE_FAMILY=1` 时，首次创建家庭新增 `FG_SETUP_TOKEN` 部署密钥校验（至少 32 字符）。没有配置初始化密钥的新单家庭服务拒绝创建家庭，避免公网抢注。
- 家长端首次创建家庭时增加部署密钥输入框，密钥不会保存在设备偏好设置中；已有家庭不受影响。
- 离线补发 SOS 时，外部提醒消息中包含事件发生 UTC 时间及延迟送达标识，降低旧求助被误认为刚发生的风险。
- 通知队列内优先处理 SOS；新增并发 SOS/定位写盘回归测试。
- 已增加 `deploy/install_server_ubuntu.sh`：可对**明确指定的 Ubuntu x86_64 主机**备份旧数据、原服务程序、安装更新、验证 loopback 健康检查并在失败时尝试回滚程序。该脚本不自动改 Nginx 或 TLS 证书。
- Windows 用户可在**自己电脑上**用 `deploy/DEPLOY_FROM_WINDOWS.ps1 -Server <服务器公网IP或域名> -Login <SSH用户名>` 执行上传与部署。它依赖你本机可用的 SSH 登录方式，不应把 SSH 私钥或密码发送给聊天助手。
- 部署完成仍需由管理员配置单独的 HTTPS 域名、手机推送通道，并实际测试两部手机的位置和紧急通知送达。

本项目录音/拍摄需要孩子端明确可见的确认操作，**不包含**隐蔽后台自动录音录像。真正的紧急告警可能因断网、关机、系统后台策略或推送网关故障延迟/失败，不应作为唯一求救渠道。
