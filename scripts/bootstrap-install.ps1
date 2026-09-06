$ErrorActionPreference = 'Stop'

$Installer = Join-Path $env:TEMP ("install-caption-action-" + [Guid]::NewGuid().ToString('N') + '.ps1')

try {
    $headers = @{
        'User-Agent' = 'CaptionAction-bootstrap'
    }
    $token = $env:GH_TOKEN
    if (-not $token) { $token = $env:GITHUB_TOKEN }
    if (-not $token) {
        try { $token = (gh auth token 2>$null) } catch { }
    }
    if ($token) { $headers['Authorization'] = "Bearer $token" }

    # Prefer main once merged; feature/android-mvp until then.
    $Branch = 'feature/android-mvp'
    Invoke-WebRequest -UseBasicParsing `
        -Uri "https://raw.githubusercontent.com/Hatsunama/Caption-Action/$Branch/scripts/install-caption-action.ps1" `
        -Headers $headers `
        -OutFile $Installer

    & $Installer
}
finally {
    if (Test-Path -LiteralPath $Installer) {
        try {
            Remove-Item -LiteralPath $Installer -Force -ErrorAction Stop
        }
        catch {
            Write-Warning "Could not remove temporary installer ${Installer}: $($_.Exception.Message)"
        }
    }
}
