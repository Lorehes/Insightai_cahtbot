param([switch]$Initialize, [switch]$ExportForDeployment)
$ErrorActionPreference = 'Stop'
# Secrets are encrypted with Windows DPAPI for this Windows account only.
$secretPath = Join-Path $PSScriptRoot '.env.sites-secrets.xml'
if (-not (Test-Path -LiteralPath $secretPath)) {
    if (-not $Initialize) { throw 'Run with -Initialize once before starting the gateway.' }
    $secrets = @{}
    foreach ($name in @('SITES_GATEWAY_TOKEN', 'SITES_GATEWAY_ADMIN_TOKEN')) {
        $bytes = New-Object byte[] 32
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $rng.GetBytes($bytes)
        $rng.Dispose()
        $hex = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
        $secrets[$name] = ConvertTo-SecureString $hex -AsPlainText -Force
    }
    $secrets | Export-Clixml -LiteralPath $secretPath
}
$saved = Import-Clixml -LiteralPath $secretPath
$plain = @{}
foreach ($name in @('SITES_GATEWAY_TOKEN', 'SITES_GATEWAY_ADMIN_TOKEN')) {
    $plain[$name] = [System.Net.NetworkCredential]::new('', $saved[$name]).Password
}
if ($ExportForDeployment) {
    # Only deployment automation should consume this output; never log it.
    $plain | ConvertTo-Json -Compress
    exit
}
if (Get-NetTCPConnection -State Listen -LocalPort 8788 -ErrorAction SilentlyContinue) {
    throw 'Port 8788 is already in use; do not start a duplicate gateway.'
}
try {
    foreach ($name in $plain.Keys) { [Environment]::SetEnvironmentVariable($name, $plain[$name], 'Process') }
    $nodePath = (Get-Command node.exe).Source
    $gatewayPath = Join-Path $PSScriptRoot 'sites-gateway.mjs'
    $gateway = Start-Process -FilePath $nodePath -ArgumentList ('"' + $gatewayPath + '"') -WindowStyle Hidden -PassThru
    Write-Output "Sites gateway started (PID $($gateway.Id))."
} finally {
    foreach ($name in $plain.Keys) { [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
    $plain.Clear()
}
