param(
    [Parameter(Mandatory=$true)][string]$Server,
    [Parameter(Mandatory=$true)][string]$Login,
    [string]$IdentityFile = ""
)
$ErrorActionPreference = "Stop"
if ($Server -notmatch '^[A-Za-z0-9.\-]+$' -or $Login -notmatch '^[A-Za-z_][A-Za-z0-9_.-]*$') {
    throw "Invalid server or login name"
}
$root = Split-Path -Parent $PSScriptRoot
$archive = Join-Path $env:TEMP "familyguard_v53_release_$PID.tar.gz"
$sshArgs = @()
if ($IdentityFile) { $sshArgs += @('-i', $IdentityFile) }
try {
    & tar.exe -czf $archive -C $root .
    if ($LASTEXITCODE -ne 0) { throw "Cannot create release archive" }
    $target = "$Login@$Server"
    & ssh.exe @sshArgs $target "mkdir -p -m 700 ~/fg-v53 && chmod 700 ~/fg-v53"
    if ($LASTEXITCODE -ne 0) { throw "SSH login failed; check identity and host fingerprint" }
    & scp.exe @sshArgs $archive "${target}:familyguard_v53_release.tar.gz"
    if ($LASTEXITCODE -ne 0) { throw "SCP upload failed" }
    & ssh.exe -tt @sshArgs $target "tar -xzf ~/familyguard_v53_release.tar.gz -C ~/fg-v53 && cd ~/fg-v53 && sudo bash deploy/install_server_ubuntu.sh"
    if ($LASTEXITCODE -ne 0) { throw "Server install failed; do not switch DNS or production routes" }
    Write-Host "Go API deployed on 127.0.0.1:8088; HTTPS reverse proxy must still be configured on server." -ForegroundColor Green
} finally {
    Remove-Item -LiteralPath $archive -ErrorAction SilentlyContinue
}
