<#
.SYNOPSIS
데모 중계 집 데스크톱 앞단(M68 설계 3.2·3.5절): DuckDNS 이름 갱신 + Caddy(HTTPS). 중계 서버는 따로 ./gradlew :viewer:relay 로 켠다.

.DESCRIPTION
  (기본)  git 밖 data/relay/ddns.json {"name": "<이름>", "token": "<토큰>"}으로 DuckDNS 이름을 이 PC의 공인 IP로 갱신하고,
          https://<이름>.duckdns.org 로 Caddy를 켠다(공유기가 TCP 80·443번을 이 PC로 넘겨야 인증서를 받는다). Ctrl+C로 끈다.
  -Local  DuckDNS 없이 http://localhost:8081 평문으로 켠다(PC에서 흘림 확인용).
Caddy는 data/tools 아래 caddy.exe(docs/LICENSES.md)를 쓴다. 토큰과 열쇠는 화면에 찍지 않는다.

.EXAMPLE
powershell -File tools\relay\home.ps1
#>
param([switch]$Local)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$caddy = Get-ChildItem (Join-Path $root 'data\tools') -Filter caddy.exe -Recurse -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
if (-not $caddy) { throw 'caddy.exe not found under data\tools (see docs\LICENSES.md)' }

function Read-Json($path) { [IO.File]::ReadAllText($path) | ConvertFrom-Json }
$port = (Read-Json (Join-Path $root 'app\src\bench\assets\relay.json')).port
$serverFile = Join-Path $root 'data\relay\server.json'
if ((Test-Path $serverFile) -and (Read-Json $serverFile).port) { $port = (Read-Json $serverFile).port }
$env:RELAY_UPSTREAM = "127.0.0.1:$port"

if ($Local) {
    $env:RELAY_HOST = 'http://localhost:8081'
    Write-Host "caddy: http://localhost:8081 -> $env:RELAY_UPSTREAM"
} else {
    $ddns = Read-Json (Join-Path $root 'data\relay\ddns.json')
    if (-not ($ddns.name -and $ddns.token)) { throw 'data\relay\ddns.json needs "name" and "token"' }
    $name = ($ddns.name -replace '\.duckdns\.org$', '')
    $r = & curl.exe -s --max-time 15 "https://www.duckdns.org/update?domains=$name&token=$($ddns.token)&verbose=true"
    $lines = @($r)
    if ($lines.Count -lt 1 -or $lines[0] -ne 'OK') { throw "DuckDNS update failed: $($lines[0])" }
    Write-Host "duckdns: $name.duckdns.org -> $($lines[1]) ($($lines[3]))"
    $env:RELAY_HOST = "$name.duckdns.org"
    Write-Host "caddy: https://$name.duckdns.org -> $env:RELAY_UPSTREAM (page: https://$name.duckdns.org/?k=<viewKey in data\relay\server.json>)"
}
& $caddy.FullName run --config (Join-Path $PSScriptRoot 'Caddyfile') --adapter caddyfile
