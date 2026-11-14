$ErrorActionPreference = 'Stop'
$androidDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$keystoreDir = Join-Path $env:USERPROFILE '.familyguard\keys'
$keystore = Join-Path $keystoreDir 'familyguard-release.jks'
$propertiesFile = Join-Path $androidDir 'keystore.properties'
$alias = 'familyguard'

function Convert-SecureToPlain([Security.SecureString]$SecureValue) {
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

$keytool = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/keytool.exe'))) {
    $keytool = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
} else {
    $found = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($found) { $keytool = $found.Source }
}
if (!$keytool) {
    $androidStudioJbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin\keytool.exe'
    if (Test-Path $androidStudioJbr) { $keytool = $androidStudioJbr }
}
if (!$keytool) { throw '找不到 keytool.exe。请在 Android Studio Terminal 中运行，或配置 JAVA_HOME 指向 JDK 17。' }

New-Item -ItemType Directory -Force -Path $keystoreDir | Out-Null
if (!(Test-Path $keystore)) {
    Write-Host '即将创建 FamilyGuard Release 私钥。密码只在本机输入，不要发给任何人。' -ForegroundColor Yellow
    $p1 = Convert-SecureToPlain (Read-Host '请输入新的签名密码（至少 16 位，字母数字下划线或连字符）' -AsSecureString)
    $p2 = Convert-SecureToPlain (Read-Host '请再次输入签名密码' -AsSecureString)
    if ($p1 -ne $p2) { throw '两次密码不一致，未创建签名文件。' }
    if ($p1.Length -lt 16) { throw '密码至少需要 16 位，请重新运行脚本。' }
    if ($p1 -notmatch '^[A-Za-z0-9_-]+$') { throw '请使用至少 16 位的英文字母、数字、下划线或连字符组成密码。' }
    $env:FG_RELEASE_PASSWORD = $p1
    try {
        & $keytool -genkeypair -v -keystore $keystore -storetype JKS `
            -storepass:env FG_RELEASE_PASSWORD -keypass:env FG_RELEASE_PASSWORD `
            -alias $alias -keyalg RSA -keysize 2048 -validity 10000 `
            -dname 'CN=FamilyGuard, OU=Android, O=FamilyGuard, L=Beijing, ST=Beijing, C=CN'
        if ($LASTEXITCODE -ne 0) { throw 'keytool 创建签名文件失败。' }
    } finally {
        Remove-Item Env:FG_RELEASE_PASSWORD -ErrorAction SilentlyContinue
    }
    $password = $p1
    $p1 = $null; $p2 = $null
} else {
    Write-Host "检测到已有签名文件，将复用，不会覆盖：$keystore" -ForegroundColor Yellow
    $password = Convert-SecureToPlain (Read-Host '请输入该签名文件的密码' -AsSecureString)
}

$env:FG_RELEASE_PASSWORD = $password
try {
    $listOutput = & $keytool -list -v -keystore $keystore -storetype JKS -alias $alias -storepass:env FG_RELEASE_PASSWORD 2>&1
    if ($LASTEXITCODE -ne 0) { throw '签名文件或密码校验失败。请勿删除现有文件；检查密码和 alias。' }
    $shaLine = $listOutput | Select-String -Pattern '(?i)SHA1:' | Select-Object -First 1
    if (!$shaLine) { throw '无法从签名证书读取 SHA1。' }
    $sha1 = ([regex]::Match($shaLine.Line, '(?i)([0-9A-F]{2}:){19}[0-9A-F]{2}')).Value.ToUpperInvariant()
    if (!$sha1) { throw 'SHA1 格式解析失败。' }

    $props = @"
# LOCAL ONLY. Never commit or share this file.
storeFile=$($keystore.Replace('\', '/'))
storeType=JKS
storePassword=$password
keyAlias=$alias
keyPassword=$password
"@
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($propertiesFile, $props + [Environment]::NewLine, $utf8NoBom)

    Write-Host ''
    Write-Host 'Release 签名文件创建/校验成功。' -ForegroundColor Green
    Write-Host "Release SHA1: $sha1" -ForegroundColor Cyan
    Write-Host "Keystore: $keystore" -ForegroundColor Cyan
    Write-Host "Gradle credentials: $propertiesFile" -ForegroundColor Cyan
    Write-Host '下一步：把此 Release SHA1 填入高德控制台“发布版安全码 SHA1”，Debug SHA1 填入“调试版安全码 SHA1”。' -ForegroundColor Yellow
    Write-Host '请离线备份 JKS 文件，并将密码保存在密码管理器中。不要上传 Git、聊天或网盘明文共享。' -ForegroundColor Yellow
} finally {
    Remove-Item Env:FG_RELEASE_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
}
