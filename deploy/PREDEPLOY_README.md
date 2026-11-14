# FamilyGuard V5.3 — 部署与验收

## 状态
- 源码和 API 可在当前评审环境测试；**未连接你的服务器，也未完成线上部署**。
- 服务端以 `127.0.0.1:8088` 启动，必须经你现有的 HTTPS Nginx 反向代理对外提供 `https://你的专用域名`。
- 不要复制其他业务的 Nginx `server {}` 块直接覆盖；应该增加专用子域名配置，避免影响原站点。

## 第一次部署
1. 把整包上传到目标 Ubuntu x86_64 服务器（例如 `/tmp/FamilyGuard_V5_3_PreDeploy`）。
2. 检查备份空间和 `/var/lib/familyguard` 是否有旧数据；部署脚本会保存数据和先前服务端到 `/var/backups/familyguard/<UTC时间>`。
3. 执行 `sudo bash deploy/install_server_ubuntu.sh`。脚本仅变更 `familyguard` 用户、`/opt/familyguard`、`/var/lib/familyguard`、`/etc/familyguard`、`familyguard.service`；不会修改 Nginx 配置或其他应用。
4. 首次部署脚本打印 `FG_SETUP_TOKEN`，**仅保存在你自己的密码管理器**，创建家庭时在家长手机输入；已有家庭无需重新创建。
5. 配置 HTTPS 反向代理及证书，示例见 `server/nginx-familyguard.example.conf`。先 `sudo nginx -t`，确认无端口冲突再 reload。
6. 服务器外部验证：`curl -fsS https://你的域名/healthz`。公网安全组只允许 443，不应开放 8088。

## 紧急通知不是默认可用
- 当前程序依赖额外配置 `FG_ALERT_URL` 及家长设备上可接收通知的客户端。须创建权限为 600 的 `/etc/familyguard/alerts.env`（示例配置可参考现有 README）。
- 推送必须在**孩子蜂窝网络、家长蜂窝网络、家长屏幕锁定**三种条件下测试真实到达率。网关返回成功 != 手机收到。
- 即使配置成功，断网、关机、强制停止、通知平台异常仍可能漏报；不能作为唯一的危险求救手段。

## 最低验收
- Android Studio `:app:assembleDebug` 完整编译；两部真实手机分别单独蜂窝联网，完成配对、持续定位和地图显示。
- 故意断开 K90 网络，按 SOS，联网恢复后仅收到一次且标记原始时间；家长锁屏下真实收到通知。
- 模拟正常围栏进出与弱网抖动；接通 Nginx 后 25MB 限额内的媒体上传和下载成功；非家长无法读取文件。
- 锁屏运行 8–24 小时、系统重启后以及网络切换后验证位置新鲜度与延迟。

## 回滚
- 部署脚本健康检查失败时自动尝试恢复旧二进制和 unit。升级前数据备份位置会在运行输出中显示。
- 需要回滚数据库时，应**先停服务并对比结构与备份时间**，不要直接覆盖正在使用的数据目录。
