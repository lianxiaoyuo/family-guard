$ErrorActionPreference = 'Stop'
$androidDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$apk = Join-Path $androidDir 'app\build\outputs\apk\release\app-release.apk'
$propsFile = Join-Path $androidDir 'keystore.properties'
if (!(Test-Path $apk)) { throw "找不到 Release APK：$apk" }
if (!(Test-Path $propsFile)) { throw '找不到 keystore.properties。' }

# Parse the local Java properties file. Password values are never printed.
$props = @{}
Get-Content $propsFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') { $props[$matches[1].Trim()] = $matches[2].Trim() }
}
if ([System.IO.Path]::IsPathRooted($props['storeFile'])) { $keystore = $props['storeFile'] } else { $keystore = Join-Path $androidDir $props['storeFile'] }
if (!(Test-Path $keystore)) { throw "找不到签名文件：$keystore" }
if (!$props['storePassword'] -or !$props['keyAlias']) { throw '签名属性缺少密码或 alias。' }

$sdk = $env:ANDROID_SDK_ROOT
if (!$sdk) { $sdk = $env:ANDROID_HOME }
if (!$sdk -and (Test-Path (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (!$sdk) { throw '找不到 Android SDK。' }
$buildTools = Join-Path $sdk 'build-tools'
$apksigner = Get-ChildItem $buildTools -Directory | Sort-Object { [version]($_.Name -replace '-.*$','') } -Descending |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } | Where-Object { Test-Path $_ } | Select-Object -First 1
if (!$apksigner) { throw '找不到 build-tools 中的 apksigner.bat。' }

# Verify the APK cryptographic signature.
$verifyOut = & $apksigner verify --verbose --print-certs $apk 2>&1
if ($LASTEXITCODE -ne 0) { $verifyOut | Write-Host; throw 'apksigner 验签失败。' }
$verifyText = $verifyOut -join "`n"
$apkSha = ([regex]::Match($verifyText, '(?im) signer #1 certificate SHA-1 digest: ([0-9a-f:]+)')).Groups[1].Value.ToUpperInvariant()
if (!$apkSha) { $apkSha = ([regex]::Match($verifyText, '(?im) certificate SHA-1 digest: ([0-9a-f:]+)')).Groups[1].Value.ToUpperInvariant() }
if (!$apkSha) { throw 'apksigner 未返回签名证书 SHA-1。' }

# Compare APK certificate against the configured keystore certificate.
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
if (!$keytool) { throw '找不到 keytool.exe，无法对照 keystore 证书。' }
$env:FG_VERIFY_PASSWORD = $props['storePassword']
try {
    $storeType = if ($props['storeType']) { $props['storeType'] } else { 'JKS' }
    $keyOut = & $keytool -list -v -keystore $keystore -storetype $storeType -alias $props['keyAlias'] -storepass:env FG_VERIFY_PASSWORD 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'keytool 无法读取配置的 keystore。' }
    $keyText = $keyOut -join "`n"
    $keySha = ([regex]::Match($keyText, '(?im)SHA1:\s*([0-9a-f:]{59})')).Groups[1].Value.ToUpperInvariant()
    if (!$keySha) { throw '无法从 keystore 读取 SHA1。' }
    if ($apkSha -ne $keySha) { throw "APK 签名证书与配置的 keystore 不一致。APK=$apkSha ; keystore=$keySha" }
} finally {
    Remove-Item Env:FG_VERIFY_PASSWORD -ErrorAction SilentlyContinue
}

Write-Host 'APK signature verification PASS.' -ForegroundColor Green
Write-Host "APK certificate SHA1: $apkSha" -ForegroundColor Cyan
Write-Host 'APK certificate SHA1 与本地 keystore 证书完全一致。' -ForegroundColor Green
Write-Host '请确认高德控制台发布版 SHA1 已登记为以上值。'
