param([switch]$Force)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
if ((Test-Path -LiteralPath $envPath) -and -not $Force) { Write-Host '.env already exists; keeping existing secrets.'; exit 0 }
function New-RandomSecret([int]$Length = 32) {
    $bytes = New-Object byte[] $Length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes)
}
$settings = @(
    'DB_USERNAME=dispatchgrid',
    "DB_PASSWORD=$(New-RandomSecret)",
    "JWT_SECRET=$(New-RandomSecret 48)",
    "ENCRYPTION_KEY=$(New-RandomSecret)",
    "MONITORING_TOKEN=$(New-RandomSecret)",
    "GRAFANA_ADMIN_PASSWORD=$(New-RandomSecret)",
    "RECEIVER_CONTROL_TOKEN=$(New-RandomSecret)",
    'API_PORT=8080', 'DASHBOARD_PORT=3000', 'GRAFANA_PORT=3001',
    'PROMETHEUS_PORT=9090', 'RECEIVER_PORT=8090', 'WORKER_CONCURRENCY=8'
)
[System.IO.File]::WriteAllLines($envPath, $settings, [System.Text.UTF8Encoding]::new($false))
Write-Host 'Created .env with independent cryptographically random development secrets. Keep this file private.'
