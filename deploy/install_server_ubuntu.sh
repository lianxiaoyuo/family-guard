#!/usr/bin/env bash
# Run ON the intended Ubuntu server after reviewing this script and transferring this release.
# Installs only loopback Go API + systemd; deliberately leaves existing Nginx untouched.
set -Eeuo pipefail
if [[ $EUID -ne 0 ]]; then echo 'Run with sudo/root on YOUR server' >&2; exit 1; fi
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$HERE/server/familyguard-server"
if [[ ! -f "$BIN" ]] || [[ ! -x "$BIN" ]]; then echo "Missing executable Linux binary: $BIN" >&2; exit 1; fi
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'Release binary only supports Linux x86_64' >&2; exit 1; }
for cmd in systemctl curl tar install sha256sum; do command -v "$cmd" >/dev/null || { echo "Missing $cmd" >&2; exit 1; }; done
umask 077
TS="$(date -u +%Y%m%dT%H%M%SZ)"
BK="/var/backups/familyguard/$TS"
mkdir -p "$BK" /opt/familyguard /var/lib/familyguard /etc/familyguard
if [[ -f /opt/familyguard/familyguard-server ]]; then cp -a /opt/familyguard/familyguard-server "$BK/previous-server"; fi
if [[ -f /etc/systemd/system/familyguard.service ]]; then cp -a /etc/systemd/system/familyguard.service "$BK/previous-service"; fi
if [[ -d /var/lib/familyguard ]]; then tar -C /var/lib -czf "$BK/data-before-update.tar.gz" familyguard; fi
id familyguard >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin familyguard
chown familyguard:familyguard /var/lib/familyguard
chmod 700 /var/lib/familyguard /etc/familyguard
# Keep setup key stable across upgrades. Print it only at initial setup.
SETTINGS=/etc/familyguard/settings.env
CREATED=0
if [[ ! -f "$SETTINGS" ]]; then
  KEY="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
  printf 'FG_SETUP_TOKEN=%s\n' "$KEY" > "$SETTINGS"
  CREATED=1
fi
chmod 600 "$SETTINGS"
if ! grep -q '^FG_SETUP_TOKEN=.' "$SETTINGS"; then echo 'setup key missing in settings.env' >&2; exit 1; fi
if [[ "$CREATED" == 1 ]]; then
  printf '\nIMPORTANT: First-family setup key (save in password manager):\n%s\n\n' "$KEY"
  echo 'Do not publish this key or paste it into chat; enter it only in the parent phone during first setup.'
fi
# Retain prior service until files are staged and backup exists.
systemctl stop familyguard.service 2>/dev/null || true
install -m 755 "$BIN" /opt/familyguard/familyguard-server
install -m 644 "$HERE/server/familyguard.service.example" /etc/systemd/system/familyguard.service
systemctl daemon-reload
systemctl enable familyguard.service >/dev/null
systemctl start familyguard.service
if ! curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8088/healthz; then
  echo 'Health check failed: rolling back binary/service if backups exist' >&2
  systemctl stop familyguard.service || true
  [[ ! -f "$BK/previous-server" ]] || cp -a "$BK/previous-server" /opt/familyguard/familyguard-server
  [[ ! -f "$BK/previous-service" ]] || cp -a "$BK/previous-service" /etc/systemd/system/familyguard.service
  systemctl daemon-reload
  [[ ! -f "$BK/previous-server" ]] || systemctl start familyguard.service || true
  exit 1
fi
printf '\n\nGo API healthy on loopback. Backups: %s\n' "$BK"
printf 'Next: configure a dedicated HTTPS Nginx domain and test externally. Do NOT open TCP/8088 publicly.\n'
