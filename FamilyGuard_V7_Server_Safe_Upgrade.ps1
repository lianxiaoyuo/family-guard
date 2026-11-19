# FamilyGuard V7 server upgrade from a Windows PowerShell client.
# Only familyguard.service and its own binary/data are touched.
# Existing medical Docker containers, Nginx and certificates are not modified.
$ErrorActionPreference = 'Stop'
$remoteScript = @'
#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

BIN=/opt/familyguard/familyguard-server
UPLOADED=/tmp/familyguard-server-v7.upload
BACKUP=/var/backups/familyguard/v7-upgrade-20261116
EXPECTED_SHA=d3ee299bee3b7eb4aafd4b6fa68b0a9e0194501a85262b07e217b58dc666d3e8
STAGED="/opt/familyguard/.familyguard-server.v7.$$.new"
ROLLBACK_TMP="/opt/familyguard/.familyguard-server.v6.$$.rollback"
STARTED_UPGRADE=0
COMMITTED=0

rollback() {
  local rc=$?
  trap - EXIT
  rm -f "$STAGED" 2>/dev/null || true
  if [ "$STARTED_UPGRADE" -eq 1 ] && [ "$COMMITTED" -ne 1 ]; then
    echo "===== 升级失败，正在恢复 V6 程序 ====="
    systemctl stop familyguard.service || true
    if install -o root -g root -m 755 "$BACKUP/familyguard-server-v6" "$ROLLBACK_TMP"; then
      mv -f "$ROLLBACK_TMP" "$BIN"
      if systemctl start familyguard.service; then
        local ok=0
        for i in $(seq 1 20); do
          if curl -fsS --max-time 2 http://172.18.0.1:8088/healthz >/dev/null 2>&1; then
            ok=1; break
          fi
          sleep 1
        done
        if [ "$ok" -eq 1 ]; then
          echo "已恢复 V6，健康接口正常。"
        else
          echo "警告：V6 已恢复但健康检查未通过，请立即检查 systemctl status 与 journalctl。"
        fi
      else
        echo "警告：恢复 V6 后启动失败，请立即检查服务日志。"
      fi
    else
      echo "严重警告：旧程序无法恢复，请检查 $BACKUP。"
    fi
    echo "一致性数据备份保留在 $BACKUP/familyguard-data-consistent.tar.gz"
  fi
  exit "$rc"
}
trap rollback EXIT

echo "===== 1. 升级前校验（不修改医疗项目） ====="
test "$(id -u)" -eq 0
systemctl is-active --quiet familyguard.service
test -f "$BIN" && test -f "$UPLOADED"
test -f "$BACKUP/familyguard-server-v6"
test -f "$BACKUP/familyguard-data-preliminary.tar.gz"
test ! -e "$BACKUP/familyguard-data-consistent.tar.gz"
test ! -e "$BACKUP/v7-upgrade-success.txt"
test -d /var/lib/familyguard/families
cmp -s "$BIN" "$BACKUP/familyguard-server-v6"
tar -tzf "$BACKUP/familyguard-data-preliminary.tar.gz" >/dev/null
printf '%s  %s\n' "$EXPECTED_SHA" "$UPLOADED" | sha256sum -c -
systemctl cat familyguard.service | grep -Fq -- '-listen 172.18.0.1:8088 -data /var/lib/familyguard'
curl -fsS --max-time 5 http://172.18.0.1:8088/healthz >/dev/null
docker inspect medical-nginx-1 >/dev/null

FAMILY_BEFORE=$(find /var/lib/familyguard/families -maxdepth 1 -type f -name '*.json' | wc -l)
if [ "$FAMILY_BEFORE" -lt 1 ]; then
  echo "没有发现现有家庭记录，停止升级并人工核对。"; exit 1
fi
MED_BEFORE=$(curl --noproxy '*' -sS --max-time 8 --resolve lianmulan.top:443:127.0.0.1 -o /dev/null -w '%{http_code}' https://lianmulan.top/)
case "$MED_BEFORE" in 000|5??) echo "医疗网站基线异常（$MED_BEFORE），停止升级。"; exit 1 ;; esac
printf '现有家庭文件数=%s；医疗站点 HTTP=%s；FamilyGuard=active\n' "$FAMILY_BEFORE" "$MED_BEFORE"

echo "===== 2. 预置 V7 可执行文件 ====="
install -o root -g root -m 755 "$UPLOADED" "$STAGED"
printf '%s  %s\n' "$EXPECTED_SHA" "$STAGED" | sha256sum -c -

echo "===== 3. 短暂停止 FamilyGuard，制作一致性数据备份 ====="
STARTED_UPGRADE=1
systemctl stop familyguard.service
if systemctl is-active --quiet familyguard.service; then
  echo "FamilyGuard 尚未停止，终止升级。"; exit 1
fi
tar -C /var/lib -czpf "$BACKUP/familyguard-data-consistent.tar.gz" familyguard
chmod 600 "$BACKUP/familyguard-data-consistent.tar.gz"
tar -tzf "$BACKUP/familyguard-data-consistent.tar.gz" >/dev/null

echo "===== 4. 替换 FamilyGuard 程序（原 systemd/Nginx 配置不变） ====="
mv -f "$STAGED" "$BIN"
systemctl start familyguard.service

READY=0
for i in $(seq 1 25); do
  if curl -fsS --max-time 2 http://172.18.0.1:8088/healthz >/dev/null 2>&1; then
    READY=1; break
  fi
  sleep 1
done
if [ "$READY" -ne 1 ]; then
  echo "V7 健康检查失败"; journalctl -u familyguard.service -n 30 --no-pager || true; exit 1
fi

echo "===== 5. 验证 V7 新旧接口、Nginx HTTPS ====="
UPDATE=$(curl -fsS --max-time 5 http://172.18.0.1:8088/v1/app/update)
case "$UPDATE" in *'"available":'*) echo "应用更新接口正常" ;; *) echo "V7 更新接口响应异常"; exit 1 ;; esac
LATEST_CODE=$(curl -sS --max-time 5 -o /dev/null -w '%{http_code}' http://172.18.0.1:8088/v1/latest)
GUARD_CODE=$(curl -sS --max-time 5 -o /dev/null -w '%{http_code}' http://172.18.0.1:8088/v1/guard)
test "$LATEST_CODE" = 401 && test "$GUARD_CODE" = 401

docker exec medical-nginx-1 wget -q -O - -T 5 http://172.18.0.1:8088/healthz
printf '\n'
curl --noproxy '*' -fsS --max-time 8 --resolve familyguard.lianmulan.top:443:127.0.0.1 https://familyguard.lianmulan.top/healthz
printf '\n'
FAMILY_AFTER=$(find /var/lib/familyguard/families -maxdepth 1 -type f -name '*.json' | wc -l)
test "$FAMILY_AFTER" -eq "$FAMILY_BEFORE"
MED_AFTER=$(curl --noproxy '*' -sS --max-time 8 --resolve lianmulan.top:443:127.0.0.1 -o /dev/null -w '%{http_code}' https://lianmulan.top/)
test "$MED_AFTER" = "$MED_BEFORE"
systemctl is-active --quiet familyguard.service
printf '家庭文件数=%s（前后相同）；原医疗站点 HTTP=%s（前后相同）\n' "$FAMILY_AFTER" "$MED_AFTER"

echo "===== 6. 标记升级成功 ====="
printf 'FamilyGuard V7 upgraded successfully. SHA256=%s\n' "$EXPECTED_SHA" > "$BACKUP/v7-upgrade-success.txt"
COMMITTED=1
echo "FamilyGuard V7 服务器升级成功。请继续用现有家长/孩子手机验证位置和围栏。"

'@
$encoded = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($remoteScript))
Write-Host "Connecting to 1.92.104.9 to upgrade ONLY familyguard.service..."
& ssh.exe -p 22 root@1.92.104.9 "echo $encoded | base64 -d | bash"
if ($LASTEXITCODE -ne 0) { throw "FamilyGuard V7 upgrade did not complete. Check messages above, including rollback status." }
Write-Host "FamilyGuard V7 backend upgrade completed successfully." -ForegroundColor Green
