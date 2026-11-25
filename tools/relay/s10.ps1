<#
.SYNOPSIS
데모 중계 S10 도우미(M67 설계 3.6절). bench 빌드가 설치된 S10이 adb로 연결돼 있어야 한다.

.DESCRIPTION
  -Url http://<PC IP>:8080  기기 파일 relay.override.json을 넣는다(업로더 켜기). 업로드 열쇠는 data/relay/server.json에서 읽는다.
  -Off                      기기 파일을 뺀다(업로더 끄기).
  -Clips                    bench 앱의 말 소리 만들기 화면을 띄우고, 끝나면 data/relay/clips로 가져온다.
  -Install                  S10과 중계 서버를 같은 코드로 맞춘다: 지금 작업 트리로 bench 빌드를 S10에 깔고(:app:installBench),
                            그 앱으로 말 소리 모음을 다시 만들고(-Clips), 돌고 있는 중계 서버를 끄고 같은 트리로 새 창에 다시 띄운다.
                            서버는 S10이 보낸 설정 층과 자기 코드로 소리를 만들므로, S10을 바꿀 때마다 이것으로 함께 바꾼다.
기기 파일은 작동 화면이 다시 열릴 때 읽는다. 열쇠는 화면에 찍지 않는다.

.EXAMPLE
powershell -File tools\relay\s10.ps1 -Url http://192.168.0.5:8080
powershell -File tools\relay\s10.ps1 -Install
#>
param([string]$Url, [switch]$Off, [switch]$Clips, [switch]$Install)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$pkg = 'hearspace.app'
$remote = "/sdcard/Android/data/$pkg/files"

if ($Install) {
    # 1) 앱: 지금 작업 트리(서버와 같은 코드)로 bench 빌드
    & (Join-Path $root 'gradlew.bat') -p $root :app:installBench -q
    if ($LASTEXITCODE -ne 0) { throw 'installBench failed' }
    $Clips = $true # 2) 말 소리 모음도 새 앱으로(문장 목록이 바뀌었을 수 있음)
}

if ($Url) {
    $server = [IO.File]::ReadAllText((Join-Path $root 'data\relay\server.json')) | ConvertFrom-Json
    if (-not $server.uploadKey) { throw 'data\relay\server.json has no uploadKey' }
    $tmp = [IO.Path]::GetTempFileName()
    $json = @{ serverUrl = $Url; uploadKey = $server.uploadKey } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText($tmp, $json, (New-Object Text.UTF8Encoding $false))
    try { adb push $tmp "$remote/relay.override.json" | Out-Null } finally { Remove-Item $tmp }
    Write-Host "relay on: $Url (reopen the user-mode screen)"
}

if ($Off) {
    adb shell rm -f "$remote/relay.override.json"
    Write-Host 'relay off (reopen the user-mode screen)'
}

if ($Clips) {
    adb shell rm -rf "$remote/relay-clips"
    adb shell am start -n "$pkg/hearspace.app.relay.ClipMakerActivity" | Out-Null
    $deadline = (Get-Date).AddMinutes(5)
    do {
        Start-Sleep -Seconds 2
        $ls = @(adb shell ls "$remote/relay-clips/" 2>$null)
    } until (($ls -match 'done.txt|failed.txt') -or ((Get-Date) -gt $deadline))
    if ($ls -match 'failed.txt') { adb shell cat "$remote/relay-clips/failed.txt"; throw 'clip maker failed' }
    if (-not ($ls -match 'done.txt')) { throw 'clip maker timed out (5 min)' }
    $out = Join-Path $root 'data\relay\clips'
    New-Item -ItemType Directory -Force $out | Out-Null
    adb pull "$remote/relay-clips/." $out | Out-Null
    Remove-Item (Join-Path $out 'done.txt') -ErrorAction SilentlyContinue
    $n = @(Get-ChildItem $out -Filter *.wav).Count
    Write-Host "clips: $n wav in data\relay\clips (restart the relay server to load them)"
    Get-Content -Encoding UTF8 (Join-Path $out 'voice.txt')
}

if ($Install) {
    # 3) 서버: 돌고 있으면 끄고 같은 트리로 새 창에 띄운다(말 소리 모음은 서버가 시작할 때 읽는다)
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -match 'hearspace\.viewer\.RelayServerKt' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host "relay server stopped (pid $($_.ProcessId))" }
    Start-Process powershell -WorkingDirectory $root -ArgumentList '-NoExit', '-Command', '.\gradlew.bat :viewer:relay'
    $hash = (git -C $root describe --always --dirty --exclude=*).Trim()
    Write-Host "S10 and relay server: same tree ($hash). Relay server starting in a new window"
}

if (-not ($Url -or $Off -or $Clips -or $Install)) { Get-Help $PSCommandPath -Detailed }
