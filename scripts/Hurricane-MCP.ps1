param([switch]$CheckOnly)

$ErrorActionPreference = 'Stop'
$gameRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$gameBin = Join-Path $gameRoot 'bin'
$gameJar = Join-Path $gameBin 'hafen.jar'
$builtJar = Join-Path $gameRoot 'build\hafen.jar'
$secretPath = Join-Path $gameBin '.bridge-token.dpapi'
$javaExecutable = 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe'

try {
    foreach ($requiredPath in @($gameJar, $secretPath, $javaExecutable)) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            throw "Required file is missing: $requiredPath"
        }
    }

    $updateAvailable = (Test-Path -LiteralPath $builtJar -PathType Leaf) -and
        ((Get-Item -LiteralPath $builtJar).LastWriteTimeUtc -gt (Get-Item -LiteralPath $gameJar).LastWriteTimeUtc) -and
        ((Get-FileHash -LiteralPath $builtJar).Hash -ne (Get-FileHash -LiteralPath $gameJar).Hash)
    $candidateJar = if ($updateAvailable) { $builtJar } else { $gameJar }

    # Validate the file that will actually be used before replacing anything.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($candidateJar)
    try {
        if ($null -eq $archive.GetEntry('haven/bridge/GameBridge.class')) {
            throw 'This hafen.jar does not contain the Hurricane bridge.'
        }
    } finally {
        $archive.Dispose()
    }

    if ($CheckOnly) {
        Write-Host 'OK: Java, bridge-enabled client and saved token file are present.'
        if ($updateAvailable) { Write-Host 'A newer build will be backed up and installed on the next normal launch.' }
        Write-Host 'No token was decrypted and no game process was started.'
        exit 0
    }

    $runningClients = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -in @('java.exe', 'javaw.exe') -and $_.CommandLine -match 'hafen\.jar'
    })
    if ($runningClients.Count -gt 0) {
        throw 'Hurricane is already running. Close it normally, then run this launcher again.'
    }
    if ($updateAvailable) {
        $backupPath = Join-Path $gameBin ('hafen-before-update-' + [Guid]::NewGuid().ToString('N') + '.jar.bak')
        Copy-Item -LiteralPath $gameJar -Destination $backupPath
        try {
            Copy-Item -LiteralPath $builtJar -Destination $gameJar -Force
            if ((Get-FileHash -LiteralPath $builtJar).Hash -ne (Get-FileHash -LiteralPath $gameJar).Hash) {
                throw 'Copied client failed verification.'
            }
        } catch {
            Copy-Item -LiteralPath $backupPath -Destination $gameJar -Force
            throw
        }
        Write-Host 'Installed the newer client build. Previous client was backed up in bin.'
    }

    # Same Windows-user DPAPI secret and port as OpenCode-Hurricane.ps1.
    # Never print the secret or place it on the Java command line.
    try {
        $bridgeSecret = (Get-Content -LiteralPath $secretPath -Raw).Trim() | ConvertTo-SecureString
        $env:HURRICANE_BRIDGE_TOKEN = [System.Net.NetworkCredential]::new('', $bridgeSecret).Password
    } catch {
        throw 'Cannot read the saved bridge token. Run as the Windows user who created it.'
    }
    if ($env:HURRICANE_BRIDGE_TOKEN -notmatch '^[A-Za-z0-9_-]{32,}$') {
        throw 'The saved bridge token has an invalid format.'
    }
    $env:HURRICANE_BRIDGE_PORT = '18711'

    Write-Host 'Starting Hurricane with the OpenCode bridge on port 18711.'
    Write-Host 'Keep this console open while playing. Launch OpenCode-Hurricane.cmd separately.'
    Push-Location -LiteralPath $gameBin
    try {
        & $javaExecutable '-Dsun.java2d.uiScale.enabled=false' '-Dsun.java2d.win.uiScaleX=1.0' '-Dsun.java2d.win.uiScaleY=1.0' '-Xss8m' '-Xms1024m' '-Xmx4096m' '--add-exports' 'java.base/java.lang=ALL-UNNAMED' '--add-exports' 'java.desktop/sun.awt=ALL-UNNAMED' '--add-exports' 'java.desktop/sun.java2d=ALL-UNNAMED' '-DrunningThroughSteam=false' '-jar' 'hafen.jar'
        $gameExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
        Remove-Item Env:HURRICANE_BRIDGE_TOKEN -ErrorAction SilentlyContinue
        if ($null -ne $bridgeSecret) { $bridgeSecret.Dispose() }
    }
    exit $gameExitCode
} catch {
    Write-Host ('Hurricane startup failed: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
