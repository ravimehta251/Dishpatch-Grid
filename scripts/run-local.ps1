param([ValidateSet('api','worker')][string]$App='api')
$ErrorActionPreference='Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
Get-Content -LiteralPath '.env' | ForEach-Object { if ($_ -match '^([^#=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process') } }
$env:DB_URL='jdbc:postgresql://localhost:5432/dispatchgrid'
$env:KAFKA_BOOTSTRAP_SERVERS='localhost:29092'
$env:REDIS_HOST='localhost'
$env:DISPATCHGRID_ALLOW_PRIVATE_DESTINATIONS='localhost'
$env:COOKIE_SECURE='false'
java -jar "backend/dispatchgrid-$App/target/dispatchgrid-$App-1.0.0-SNAPSHOT.jar"
