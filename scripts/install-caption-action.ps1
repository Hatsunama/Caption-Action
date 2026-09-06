$ErrorActionPreference = 'Stop'

$PackageName = 'com.hatsunama.captionaction'
$ReleaseUrl = 'https://api.github.com/repos/Hatsunama/Caption-Action/releases/tags/v0.1.0-mvp'
$DownloadDir = $null
$Apk = $null

try {
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        throw 'Install Android platform-tools and add adb to PATH first.'
    }

    $listing = @(adb devices)
    if ($LASTEXITCODE -ne 0) { throw 'Could not list Android devices.' }

    $devices = @($listing | ForEach-Object {
        if ($_ -match '^(\S+)\s+(device|unauthorized|offline|recovery|sideload|bootloader)\b') {
            [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
        }
    })

    if ($devices.Count -eq 0) { throw 'Connect your phone and enable USB debugging.' }
    if ($devices.Count -gt 1) { throw 'Disconnect extra devices; this script will not guess.' }
    if ($devices[0].State -ne 'device') { throw 'Authorize USB debugging on your phone.' }

    $Serial = $devices[0].Serial

    $state = (adb -s $Serial get-state) -join ''
    if ($LASTEXITCODE -ne 0 -or $state.Trim() -ne 'device') {
        throw 'The device is not ready.'
    }

    $booted = (adb -s $Serial shell getprop sys.boot_completed) -join ''
    if ($LASTEXITCODE -ne 0 -or $booted.Trim() -ne '1') {
        throw 'Wait until the phone finishes booting.'
    }

    $abis = (adb -s $Serial shell getprop ro.product.cpu.abilist) -join ''
    if ($LASTEXITCODE -ne 0 -or $abis -notmatch 'arm64-v8a') {
        throw 'This APK requires an arm64 Android device.'
    }

    $headers = @{
        'User-Agent' = 'CaptionAction-installer'
        'Accept' = 'application/vnd.github+json'
    }
    $token = $env:GH_TOKEN
    if (-not $token) { $token = $env:GITHUB_TOKEN }
    if (-not $token) {
        try { $token = (gh auth token 2>$null) } catch { }
    }
    if ($token) { $headers['Authorization'] = "Bearer $token" }

    $release = Invoke-RestMethod -Uri $ReleaseUrl -Headers $headers

    $assets = @($release.assets | Where-Object { $_.name -eq 'caption-action-android.apk' })
    if ($assets.Count -ne 1) { throw 'The release APK was not found.' }

    $ExpectedHash = 'C2675FD2CE87BD777F2621AD92EC39B3CCF583D123021AB3F3817882B67BFEE7'

    $DownloadDir = Join-Path ([IO.Path]::GetTempPath()) ('caption-action-install-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $DownloadDir | Out-Null
    $Apk = Join-Path $DownloadDir 'caption-action-android.apk'

    $downloadHeaders = @{
        'User-Agent' = 'CaptionAction-installer'
        'Accept' = 'application/octet-stream'
    }
    if ($token) { $downloadHeaders['Authorization'] = "Bearer $token" }

    Invoke-WebRequest -Uri $assets[0].url -Headers $downloadHeaders -OutFile $Apk -UseBasicParsing

    if ((Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash -ne $ExpectedHash) {
        throw 'Checksum mismatch. Installation refused.'
    }

    adb -s $Serial install -r --no-streaming $Apk
    if ($LASTEXITCODE -ne 0) {
        throw 'Update failed. Do NOT uninstall or clear app data.'
    }

    adb -s $Serial shell pm enable $PackageName
    if ($LASTEXITCODE -ne 0) { throw 'Could not re-enable Caption Action.' }

    adb -s $Serial shell am start -n "$PackageName/.ui.home.HomeActivity"
    if ($LASTEXITCODE -ne 0) { throw 'Open Caption Action manually on your phone.' }

    adb -s $Serial shell dumpsys package $PackageName |
        Select-String 'versionName=', 'versionCode=', 'targetSdk='
}
finally {
    if ($Apk -and (Test-Path -LiteralPath $Apk)) {
        Remove-Item -LiteralPath $Apk -Force
    }
    if ($DownloadDir -and (Test-Path -LiteralPath $DownloadDir)) {
        Remove-Item -LiteralPath $DownloadDir
    }
}
