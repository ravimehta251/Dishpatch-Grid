param([ValidateSet('sustained','burst')][string]$Profile = 'sustained', [int]$Rate = 5, [string]$Duration = '30s')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$credentialPath = Join-Path $projectRoot '.local/demo-secrets.json'
if (-not (Test-Path -LiteralPath $credentialPath)) { throw 'Run python scripts/demo.py --prepare-load first.' }
$credentials = Get-Content -Raw -LiteralPath $credentialPath | ConvertFrom-Json
$resultsPath = Join-Path $projectRoot 'performance/results'
New-Item -ItemType Directory -Force -Path $resultsPath | Out-Null
$runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$env:API_KEY = $credentials.apiKey
try {
    docker run --rm --network dispatchgrid_default -e API_KEY -e BASE_URL=http://api:8080 -e "PROFILE=$Profile" -e "RATE=$Rate" -e "DURATION=$Duration" -e "RUN_ID=$runId" -e "SUMMARY_PATH=/results/$runId-summary.json" -v "${projectRoot}/performance/k6:/scripts:ro" -v "${resultsPath}:/results" grafana/k6:1.0.0 run /scripts/ingestion.js --out "json=/results/$runId-raw.json"
    if ($LASTEXITCODE -ne 0) { throw "k6 exited with code $LASTEXITCODE" }
} finally { Remove-Item Env:API_KEY -ErrorAction SilentlyContinue }
Write-Host "Saved real k6 outputs under performance/results/$runId-*.json"
