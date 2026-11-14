# FamilyGuard V6.0.1.2 - publish reviewed source to GitHub, then deploy the Go API over SSH.
# Runs on the user's Windows PC. Uses the user's GitHub credentials and local OpenSSH keys/agent.
# Does not upload AMap keys, local.properties, keystores, database files, or compiled binaries to GitHub.
# Server install uses SCP, so the Ubuntu server does NOT need outbound access to GitHub.
[CmdletBinding()]
param(
    [string]$Server = '1.92.104.9',
    [string]$Login = '',
    [string]$IdentityFile = '',
    [ValidateRange(1, 65535)][int]$SshPort = 22,
    [string]$GitProxy = 'socks5h://127.0.0.1:10808',
    [switch]$PushOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoUrl = 'https://github.com/lianxiaoyuo/protectApp.git'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ServerBinary = Join-Path $ProjectRoot 'server\familyguard-server'
$Installer = Join-Path $ProjectRoot 'deploy\install_server_ubuntu.sh'
$ServiceUnit = Join-Path $ProjectRoot 'server\familyguard.service.example'

if ($Server -notmatch '^[A-Za-z0-9.\-]+$') { throw 'Invalid server IP/hostname.' }
if (-not $PushOnly -and $Login -notmatch '^[A-Za-z_][A-Za-z0-9_.-]*$') { throw 'For deployment, provide the actual SSH username with -Login.' }
if ($PushOnly -and -not [string]::IsNullOrWhiteSpace($Login) -and $Login -notmatch '^[A-Za-z_][A-Za-z0-9_.-]*$') { throw 'Invalid SSH login name.' }
foreach ($required in @($ServerBinary, $Installer, $ServiceUnit, (Join-Path $ProjectRoot '.gitignore'), (Join-Path $ProjectRoot 'android\app\build.gradle'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required V6.0.1.2 file missing: $required" }
}

$git = (Get-Command git.exe -ErrorAction Stop).Source

function Invoke-Checked {
    param([Parameter(Mandatory = $true)][string]$Exe, [Parameter(Mandatory = $true)][string[]]$Arguments)
    & $Exe @Arguments
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw "$Exe exited with status $code" }
}


function Invoke-Ssh {
    param([Parameter(Mandatory = $true)][string]$RemoteCommand, [switch]$Tty)
    $sshArgsLocal = @($script:SshBaseArgs)
    if ($Tty) { $sshArgsLocal += '-tt' }
    $sshArgsLocal += @($script:Target, $RemoteCommand)
    Invoke-Checked -Exe $script:ssh -Arguments $sshArgsLocal
}

function Invoke-SshOutput {
    param([Parameter(Mandatory = $true)][string]$RemoteCommand)
    $sshArgsLocal = @($script:SshBaseArgs) + @($script:Target, $RemoteCommand)
    $output = & $script:ssh @sshArgsLocal
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw "ssh.exe exited with status $code" }
    return ($output -join "`n")
}

Write-Host '===== 1/2: Prepare and publish source to GitHub =====' -ForegroundColor Cyan
if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot '.git'))) {
    Invoke-Checked -Exe $git -Arguments @('-C', $ProjectRoot, 'init', '-b', 'main')
}

$branch = (& $git -C $ProjectRoot branch --show-current).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot determine local Git branch.' }
if ($branch -ne 'main') {
    & $git -C $ProjectRoot rev-parse --verify HEAD 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { throw "Local branch is '$branch', not 'main'. Stopping rather than rewriting history." }
    Invoke-Checked -Exe $git -Arguments @('-C', $ProjectRoot, 'branch', '-M', 'main')
}

# Configure the user's local V2RayN SOCKS5 proxy for GitHub HTTPS only.
# SSH/SCP to the deployment server remain direct and are not sent through this proxy.
if (-not [string]::IsNullOrWhiteSpace($GitProxy)) {
    Invoke-Checked -Exe $git -Arguments @('-C', $ProjectRoot, 'config', '--local', 'http.proxy', $GitProxy)
    Write-Host "GitHub Git proxy: $GitProxy" -ForegroundColor DarkCyan
} else {
    Write-Host 'GitHub Git proxy: disabled (using the normal network route).' -ForegroundColor DarkYellow
}

# List remotes first. Do not query a missing origin: Windows PowerShell can turn
# Git's expected stderr into a terminating NativeCommandError under Stop preference.
$remoteNames = @(& $git -C $ProjectRoot remote)
if ($LASTEXITCODE -ne 0) { throw 'Cannot list configured Git remotes.' }
if ($remoteNames -notcontains 'origin') {
    Invoke-Checked -Exe $git -Arguments @('-C', $ProjectRoot, 'remote', 'add', 'origin', $RepoUrl)
    Write-Host "Added origin: $RepoUrl" -ForegroundColor Green
} else {
    $existingUrl = (& $git -C $ProjectRoot remote get-url origin)
    if ($LASTEXITCODE -ne 0) { throw 'The existing origin remote could not be read.' }
    $existingUrlText = ($existingUrl -join "`n").Trim()
    if ($existingUrlText.ToLowerInvariant() -ne $RepoUrl.ToLowerInvariant()) {
        throw "origin points to '$existingUrlText', not the expected repository '$RepoUrl'. No push was attempted."
    }
}

# Ensure critical local-only files are ignored before staging.
$mustIgnore = @(
    'android/local.properties',
    'android/amap-key.properties',
    'android/keystore.properties',
    'android/keystore/familyguard-release.jks',
    'deploy/.env',
    'server/familyguard-server',
    'server/familyguard-server.exe',
    'server/data/families.json'
)
foreach ($path in $mustIgnore) {
    & $git -C $ProjectRoot check-ignore -q -- $path
    if ($LASTEXITCODE -ne 0) { throw ".gitignore does not protect '$path'. Refusing to stage or push." }
}

# Explicit allow-list of project source directories. Includes the linked V6 README.
Invoke-Checked -Exe $git -Arguments @('-C', $ProjectRoot, 'add', '--', '.github', '.gitignore', 'README.md', 'README_V6_最终交付与部署说明.md', 'android', 'server', 'deploy', 'docs', 'tools')
$staged = @(& $git -C $ProjectRoot diff --cached --name-only)
$forbidden = @($staged | Where-Object {
    $_ -match '(^|/)(local\.properties|amap-key\.properties|keystore\.properties|\.env|settings\.env|alerts\.env|families\.json|[^/]*\.db|[^/]*\.jks|[^/]*\.keystore|[^/]*\.pem|[^/]*\.key)$' -or
    $_ -match '(^|/)(data|local-test-data|keystore)/' -or
    $_ -match '(^|/)(familyguard-server(\.exe)?|[^/]+\.apk|[^/]+\.aab|[^/]+\.jks|[^/]+\.zip)$' -or
    $_ -match '(^|/)build/'
})
if ($forbidden.Count -gt 0) { throw "Refusing to publish sensitive/build files: $($forbidden -join ', ')" }

if ($staged.Count -gt 0) {
    $gitName = (& $git -C $ProjectRoot config user.name 2>$null)
    $gitEmail = (& $git -C $ProjectRoot config user.email 2>$null)
    if ([string]::IsNullOrWhiteSpace(($gitName -join '')) -or [string]::IsNullOrWhiteSpace(($gitEmail -join ''))) {
        throw 'Git author is not configured. Run: git config --global user.name "Your Name" and git config --global user.email "your-email@example.com".'
    }
    Write-Host "Files staged for commit: $($staged.Count)" -ForegroundColor Yellow
    Invoke-Checked -Exe $git -Arguments @('-C', $ProjectRoot, 'commit', '-m', 'chore(release): publish FamilyGuard V6.0.1.2 source')
} else {
    Write-Host 'No new source changes to commit; will verify/push the existing local commit.' -ForegroundColor Yellow
}

$commit = (& $git -C $ProjectRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') { throw 'Cannot resolve local commit SHA.' }

# If main already exists remotely, require it to be an ancestor. Never force-push.
$remoteHead = & $git -C $ProjectRoot ls-remote --heads origin main
if ($LASTEXITCODE -ne 0) { throw 'Cannot read GitHub main branch. Check GitHub authentication/network.' }
if ($remoteHead) {
    Invoke-Checked -Exe $git -Arguments @('-C', $ProjectRoot, 'fetch', 'origin', 'main:refs/remotes/origin/main')
    & $git -C $ProjectRoot merge-base --is-ancestor origin/main HEAD
    if ($LASTEXITCODE -ne 0) { throw 'GitHub main contains commits not present locally. Refusing to overwrite or force-push.' }
}
Invoke-Checked -Exe $git -Arguments @('-C', $ProjectRoot, 'push', '-u', 'origin', 'main')
Write-Host "GitHub push successful. Commit: $commit" -ForegroundColor Green
Write-Host "Repository: https://github.com/lianxiaoyuo/protectApp/commit/$commit" -ForegroundColor Green

if ($PushOnly) {
    Write-Host 'PushOnly selected. Server has NOT been changed.' -ForegroundColor Yellow
    exit 0
}

if ($Login -notmatch '^[A-Za-z_][A-Za-z0-9_.-]*$') { throw 'For deployment, provide the actual SSH username with -Login.' }
$ssh = (Get-Command ssh.exe -ErrorAction Stop).Source
$scp = (Get-Command scp.exe -ErrorAction Stop).Source
$SshBaseArgs = @('-p', [string]$SshPort)
$ScpBaseArgs = @('-P', [string]$SshPort)
if (-not [string]::IsNullOrWhiteSpace($IdentityFile)) {
    $identity = (Resolve-Path -LiteralPath $IdentityFile).Path
    $SshBaseArgs += @('-i', $identity)
    $ScpBaseArgs += @('-i', $identity)
}
$Target = "$Login@$Server"

Write-Host "`n===== 2/2: Preflight and deploy Go API to $Target =====" -ForegroundColor Cyan
$localHash = (Get-FileHash -LiteralPath $ServerBinary -Algorithm SHA256).Hash.ToLowerInvariant()
$timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss')
$remoteDir = "/tmp/familyguard-v6-$timestamp"
$stageRoot = Join-Path $env:TEMP ("familyguard-v6-stage-" + [guid]::NewGuid().ToString('N'))

try {
    New-Item -ItemType Directory -Force -Path (Join-Path $stageRoot 'server'), (Join-Path $stageRoot 'deploy') | Out-Null
    Copy-Item -LiteralPath $ServerBinary -Destination (Join-Path $stageRoot 'server\familyguard-server')
    Copy-Item -LiteralPath $ServiceUnit -Destination (Join-Path $stageRoot 'server\familyguard.service.example')
    Copy-Item -LiteralPath $Installer -Destination (Join-Path $stageRoot 'deploy\install_server_ubuntu.sh')

    $preflight = @'
set -eu
[ "$(uname -s)" = Linux ] || { echo 'Target is not Linux'; exit 11; }
[ "$(uname -m)" = x86_64 ] || { echo 'Target is not x86_64'; exit 12; }
for c in systemctl curl tar install sha256sum ss grep; do command -v "$c" >/dev/null || { echo "Missing command: $c"; exit 13; }; done
if [ "$(id -u)" -ne 0 ]; then command -v sudo >/dev/null || { echo 'sudo is required for a non-root SSH user'; exit 14; }; fi
printf '\n--- Current FamilyGuard service ---\n'
systemctl is-active familyguard.service 2>/dev/null || true
printf '\n--- Port 8088 ---\n'
if ss -lnt '( sport = :8088 )' | grep -q LISTEN; then
  if ! systemctl is-active --quiet familyguard.service; then echo 'Port 8088 is occupied by a process other than the active familyguard.service'; exit 15; fi
  echo '8088 is currently used by familyguard.service; this deployment will restart it.'
else
  echo '8088 is free.'
fi
printf '\n--- Existing data ---\n'
if [ -d /var/lib/familyguard ]; then du -sh /var/lib/familyguard; else echo '/var/lib/familyguard does not exist yet.'; fi
printf '\n--- Disk space ---\n'
df -h /var
'@
    Write-Host (Invoke-SshOutput -RemoteCommand $preflight)

    $confirm = Read-Host "将备份并安装/更新 FamilyGuard systemd 服务（不会修改 Nginx）。确认继续请输入 YES"
    if ($confirm -cne 'YES') { throw 'Deployment cancelled by user. No server files were installed.' }

    Invoke-Ssh -RemoteCommand "mkdir -p -m 700 '$remoteDir'"
    $scpArgs = @($ScpBaseArgs) + @('-r', (Join-Path $stageRoot 'server'), (Join-Path $stageRoot 'deploy'), "${Target}:$remoteDir/")
    Invoke-Checked -Exe $scp -Arguments $scpArgs
    Invoke-Ssh -RemoteCommand "chmod 700 '$remoteDir/server/familyguard-server' '$remoteDir/deploy/install_server_ubuntu.sh'"

    $remoteHashText = Invoke-SshOutput -RemoteCommand "sha256sum '$remoteDir/server/familyguard-server'"
    $remoteHash = (($remoteHashText.Trim() -split '\s+')[0]).ToLowerInvariant()
    if ($remoteHash -ne $localHash) { throw "SCP binary checksum mismatch. Local=$localHash Remote=$remoteHash. Installer was not run." }
    Write-Host "Linux binary SHA256 verified: $localHash" -ForegroundColor Green

    $installCommandTemplate = @'
if [ "$(id -u)" -eq 0 ]; then
  bash "__INSTALLER__"
else
  sudo bash "__INSTALLER__"
fi
'@
    $installCommand = $installCommandTemplate.Replace('__INSTALLER__', "$remoteDir/deploy/install_server_ubuntu.sh")
    Invoke-Ssh -RemoteCommand $installCommand -Tty

    $health = Invoke-SshOutput -RemoteCommand "systemctl is-active --quiet familyguard.service && curl -fsS --max-time 5 http://127.0.0.1:8088/healthz"
    Write-Host "Server health check: $health" -ForegroundColor Green
    Invoke-Ssh -RemoteCommand "rm -rf '$remoteDir'"
    Write-Host 'Deployment completed. Existing Nginx was NOT changed.' -ForegroundColor Green
    Write-Host 'Next required step: configure your dedicated HTTPS domain in Nginx and verify https://YOUR_DOMAIN/healthz.' -ForegroundColor Yellow
    Write-Host 'If this was the first install, save the FG_SETUP_TOKEN printed by the installer in your password manager. Do not paste it into chat.' -ForegroundColor Yellow
}
finally {
    if (Test-Path -LiteralPath $stageRoot) { Remove-Item -LiteralPath $stageRoot -Recurse -Force -ErrorAction SilentlyContinue }
}
