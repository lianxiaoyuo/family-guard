$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$target = Join-Path $root 'gradle\wrapper\gradle-wrapper.jar'
$expected = '2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046'
$url = 'https://services.gradle.org/distributions/gradle-8.11.1-wrapper.jar'
if (Test-Path -LiteralPath $target) {
    $actual = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -eq $expected) { Write-Host 'Gradle Wrapper verified'; exit 0 }
    throw 'Wrapper SHA256 mismatch, refusing to execute'
}
New-Item -ItemType Directory -Force (Split-Path -Parent $target) | Out-Null
$tmp = "$target.download"
try {
    Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing
    $actual = (Get-FileHash -LiteralPath $tmp -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) { throw "Wrapper SHA256 mismatch: $actual" }
    Move-Item -LiteralPath $tmp -Destination $target
    Write-Host 'Official Gradle Wrapper downloaded and verified'
} finally { if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force } }
