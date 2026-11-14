# One-time creation of a cryptographically random private ntfy topic.
# No location data, media data or child names are included in push messages.
# Run with: powershell -NoProfile -ExecutionPolicy Bypass -File .\SETUP_EMERGENCY_ALERT_WINDOWS.ps1
$ErrorActionPreference = 'Stop'
$folder = Split-Path -Parent $MyInvocation.MyCommand.Path
$destination = Join-Path $folder 'local-alert-settings.cmd'
if (Test-Path $destination) {
    Write-Host 'Alert configuration already exists. Reuse it to keep the same subscription.'
    Get-Content $destination | Select-String '^set "FG_ALERT_URL=' | ForEach-Object { Write-Host $_.Line }
    exit 0
}
$bytes = New-Object byte[] 24
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$topic = 'familyguard-' + ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
$url = "https://ntfy.sh/$topic"
$lines = @('@echo off', ('set "FG_ALERT_URL=' + $url + '"'))
[System.IO.File]::WriteAllLines($destination, $lines, [System.Text.Encoding]::ASCII)
Write-Host ''
Write-Host 'Emergency alert topic created. Keep the exact topic URL private.'
Write-Host ('Notification address: ' + $url)
Write-Host ''
Write-Host '1. Install the official ntfy Android app on the parent Xiaomi phone.'
Write-Host ('2. Subscribe to this FULL URL: ' + $url)
Write-Host '3. Allow notifications and enable Instant delivery / auto-start if available.'
Write-Host '4. Run START_SERVER_LOCAL_WINDOWS.cmd or configure FG_ALERT_URL on your Linux production server.'
Write-Host '5. Send an SOS from the child phone and make sure the parent receives it with the app closed.'
Write-Host 'NOTE: ntfy.sh may be unreachable on some networks. For production, prefer your own HTTPS ntfy gateway.'
