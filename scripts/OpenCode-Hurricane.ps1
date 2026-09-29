param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$OpenCodeArgs
)

$ErrorActionPreference = 'Stop'
$hurricaneRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$secretPath = Join-Path $hurricaneRoot 'bin\.bridge-token.dpapi'
$bridgeSecret = (Get-Content -LiteralPath $secretPath -Raw).Trim() | ConvertTo-SecureString
$env:HURRICANE_BRIDGE_TOKEN = [System.Net.NetworkCredential]::new('', $bridgeSecret).Password
$env:HURRICANE_BRIDGE_PORT = '18711'
$bunExecutable = (& bun -e 'console.log(process.execPath)').Trim()
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $bunExecutable)) {
    throw 'Bun could not be located.'
}
$env:PATH = (Split-Path -Parent $bunExecutable) + ';' + $env:PATH

Push-Location -LiteralPath $hurricaneRoot
try {
    if ($OpenCodeArgs.Count -eq 0) {
        # A private server inherits this client's bridge environment.
        & opencode --standalone $hurricaneRoot
    } else {
        & opencode @OpenCodeArgs
    }
} finally {
    Pop-Location
}
