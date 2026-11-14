$ErrorActionPreference = 'Stop'
$androidDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $androidDir
try {
    $key = $null
    foreach ($name in @('local.properties', 'amap-key.properties')) {
        $keyFile = Join-Path $androidDir $name
        if (Test-Path $keyFile) {
            $line = Get-Content $keyFile -Encoding ASCII | Where-Object { $_ -match '^AMAP_KEY=' } | Select-Object -First 1
            if ($line) {
                $candidate = $line.Substring('AMAP_KEY='.Length).Trim()
                if ($key -and $key -ne $candidate) { throw 'local.properties 与 amap-key.properties 中的 AMAP_KEY 不一致' }
                $key = $candidate
            }
        }
    }
    if (!$key) { throw 'local.properties / amap-key.properties 均未配置 AMAP_KEY' }
    if ($key -notmatch '^[A-Za-z0-9]{20,}$') { throw 'AMAP_KEY 格式不正确' }
    $build = Get-Content (Join-Path $androidDir 'app/build.gradle') -Raw -Encoding UTF8
    $manifest = Get-Content (Join-Path $androidDir 'app/src/main/AndroidManifest.xml') -Raw -Encoding UTF8
    if ($build -notmatch 'amap-key\.properties' -or $build -notmatch 'verifyAmapConfiguration') { throw 'Gradle 未配置 AMap Key 加载或校验任务' }
    if ($manifest -notmatch 'com\.amap\.api\.v2\.apikey' -or $manifest -notmatch '\$\{AMAP_KEY\}') { throw 'Manifest 高德 Key 占位符缺失' }
    if ($build -notmatch "applicationId 'com\.familyguard\.app'") { throw 'applicationId 与高德控制台包名不一致' }
    $expectedSha1 = 'B6:3B:7C:2E:67:BE:D6:59:D4:72:CC:5E:1D:C4:7F:20:43:5A:0B:63'
    $debugKeystore = Join-Path $env:USERPROFILE '.android/debug.keystore'
    $keytool = $null
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/keytool.exe'))) {
        $keytool = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
    } else {
        $found = Get-Command keytool.exe -ErrorAction SilentlyContinue
        if ($found) { $keytool = $found.Source }
    }
    if ($keytool -and (Test-Path $debugKeystore)) {
        $shaLine = & $keytool -list -v -keystore $debugKeystore -alias androiddebugkey -storepass android -keypass android 2>$null |
            Select-String 'SHA1:' | Select-Object -First 1
        if ($shaLine) {
            $actualSha1 = ([regex]::Match($shaLine.Line, '(?i)([0-9A-F]{2}:){19}[0-9A-F]{2}')).Value.ToUpperInvariant()
            if ($actualSha1 -ne $expectedSha1) { throw "当前电脑 debug.keystore SHA1 与本次高德控制台登记值不一致。实际值：$actualSha1" }
            Write-Host 'Debug SHA1 与项目登记值一致。' -ForegroundColor Green
        } else { Write-Warning '无法读取 debug.keystore SHA1；请手动核对高德控制台。' }
    } else { Write-Warning '未找到 keytool 或 debug.keystore，跳过本机 SHA1 对照；请手动核对高德控制台。' }
    Write-Host '本地静态检查通过：AMAP_KEY 已配置、包名和 Manifest 注入入口正确（不显示 Key）。' -ForegroundColor Green
    & .\gradlew.bat :app:verifyAmapConfiguration
    if ($LASTEXITCODE -ne 0) { throw 'Gradle 高德配置验证失败' }
    Write-Host '下一步可执行 .\gradlew.bat :app:assembleDebug 构建 APK。' -ForegroundColor Green
} finally { Pop-Location }
