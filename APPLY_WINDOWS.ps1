# Apply a minimal client-only UX patch to an existing FamilyGuard_V6_Complete checkout.
# This script does NOT modify the deployed Go server, nginx, DNS, or any secrets.
param(
    [string]$Project = 'C:\Users\74787\Downloads\d\FamilyGuard_V6_Complete',
    [switch]$BuildApk
)
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'android\app\src\main\java\com\familyguard\app'
$target = Join-Path $Project 'android\app\src\main\java\com\familyguard\app'
if (!(Test-Path $source -PathType Container)) { throw '请先完整解压此补丁 ZIP，再运行脚本。' }
if (!(Test-Path $target -PathType Container)) { throw "找不到项目 Java 目录：$target" }
$names = @('MainActivity.java', 'ServerAddress.java', 'Api.java')
$originalHashes = @{
    'ServerAddress.java' = '3DC66EC32E74C26027DB3B02DE286B692E4C65678D6EDF08B18B4F344881425A'
    'Api.java' = '96CCD039128C5096E876C3ED441644D06037348EFF1A2D3CE1D521ECDD06A2DB'
    'MainActivity.java' = '1815BFCB2F64290C8F0C3D05A550101F06C136944932688F09900EF47B1FD233'
}
# Abort on a modified working tree rather than silently replacing user changes.
foreach ($name in $names) {
    $p = Join-Path $target $name
    if (!(Test-Path $p -PathType Leaf)) { throw "缺少原文件：$p" }
    # Windows Git may convert LF to CRLF; compare normalized text bytes.
    $fileText = [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
    $normalized = $fileText.Replace("`r`n", "`n").Replace("`r", "`n")
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hashBytes = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($normalized))
        $actual = ([BitConverter]::ToString($hashBytes)).Replace('-', '')
    } finally { $sha.Dispose() }
    $expected = $originalHashes[$name]
    if ($actual -ne $expected) {
        throw "原文件 $name 与 V6.0.1.2 基线不同。为防止覆盖你的更改，已停止。请不要强制覆盖，发我哈希值再合并。"
    }
}
$backup = Join-Path $Project ('familyguard-ux-backup-' + (Get-Date -Format 'yyyyMMdd_HHmmss'))
New-Item -ItemType Directory -Path $backup | Out-Null
foreach ($name in $names) {
    Copy-Item (Join-Path $target $name) (Join-Path $backup $name)
}
try {
    foreach ($name in $names) {
        Copy-Item (Join-Path $source $name) (Join-Path $target $name) -Force
    }
    Write-Host '已完成：自动连接固定 HTTPS 服务器、角色选择、家长/孩子分开配对；已备份原始 Java 文件。' -ForegroundColor Green
    Write-Host "备份目录：$backup"
    if ($BuildApk) {
        Push-Location (Join-Path $Project 'android')
        try {
            & .\BUILD_APK_WINDOWS.cmd
            if ($LASTEXITCODE -ne 0) { throw "Android Debug APK 构建失败，退出码 $LASTEXITCODE" }
            $apk = Join-Path $Project 'android\app\build\outputs\apk\debug\app-debug.apk'
            if (!(Test-Path $apk)) { throw "构建命令返回成功但未发现 APK：$apk" }
            Write-Host "APK：$apk" -ForegroundColor Green
        } finally { Pop-Location }
    } else {
        Write-Host '下一步：用 Android Studio 构建 APK，或重新执行本脚本并添加 -BuildApk 参数。'
    }
} catch {
    Write-Warning '应用/构建遇到错误：恢复原始 Java 文件。'
    foreach ($name in $names) {
        Copy-Item (Join-Path $backup $name) (Join-Path $target $name) -Force
    }
    throw
}
