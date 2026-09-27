<#
.SYNOPSIS
    Android 기기 연결 확인: adb 연결·승인 상태, 기기 모델, ARCore(Google Play 서비스(AR)), 앱 설치, 저장 공간.

.DESCRIPTION
    저장소 루트에서 실행한다.

      powershell -ExecutionPolicy Bypass -File tools\setup\check-device.ps1             # 확인만
      powershell -ExecutionPolicy Bypass -File tools\setup\check-device.ps1 -Install    # 디버그 앱 빌드·설치 + 카메라 권한 부여
      powershell -ExecutionPolicy Bypass -File tools\setup\check-device.ps1 -Launch     # 앱(개발 모드 홈) 실행
      powershell -ExecutionPolicy Bypass -File tools\setup\check-device.ps1 -Serial R3CM...   # 기기가 여러 대일 때

    문제가 없으면 종료 코드 0, 있으면 1.
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$Install,
    [switch]$Launch,
    [int]$WaitSeconds = 20
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$AppId = 'walkassist.app'
$ArcoreId = 'com.google.ar.core'
$ExpectedModel = 'SM-G977N'            # 기준 기기 (MVP_SPEC §2.1)
$SessionsDir = "/storage/emulated/0/Android/data/$AppId/files/sessions"
$MinFreeGb = 5                          # 10분 녹화 MP4만 약 1.2 GB (docs/FORMAT.md)

$problems = New-Object System.Collections.Generic.List[string]
function Write-Step([string]$msg) { Write-Host "`n== $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg) { Write-Host "   OK  $msg" -ForegroundColor Green }
function Write-Bad([string]$msg, [string[]]$hints) {
    Write-Host "   !!  $msg" -ForegroundColor Yellow
    foreach ($h in $hints) { Write-Host "       - $h" }
    $problems.Add($msg)
}

# ---------------------------------------------------------------- adb 찾기

Write-Step '1. adb'
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    foreach ($root in @($env:ANDROID_HOME, "$env:LOCALAPPDATA\Android\Sdk")) {
        if ($root -and (Test-Path "$root\platform-tools\adb.exe")) { $adb = "$root\platform-tools\adb.exe"; break }
    }
}
if (-not $adb) {
    Write-Bad 'adb를 찾지 못함' @('먼저 tools\setup\setup-windows.ps1 을 실행하세요')
    exit 1
}
Write-Ok $adb
& $adb start-server | Out-Null

function Invoke-Adb([string[]]$adbArgs) {
    $all = @()
    if ($script:deviceSerial) { $all += @('-s', $script:deviceSerial) }
    $out = & $adb @($all + $adbArgs)
    return (@($out) -join "`n").Trim()
}
function Get-Prop([string]$name) { return Invoke-Adb @('shell', 'getprop', $name) }

# ---------------------------------------------------------------- 기기 목록

function Get-AdbDevices {
    $lines = & $adb devices -l
    $list = @()
    foreach ($l in $lines) {
        if ($l -match '^(\S+)\s+(device|unauthorized|offline|no permissions|authorizing|recovery|sideload|bootloader)\b(.*)$') {
            $model = if ($Matches[3] -match 'model:(\S+)') { $Matches[1] } else { '' }
            $list += [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2]; Model = $model }
        }
    }
    return , $list
}

Write-Step '2. 기기 연결'
$devices = Get-AdbDevices
$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ((@($devices | Where-Object { $_.State -eq 'device' }).Count -eq 0) -and ((Get-Date) -lt $deadline)) {
    if ($devices.Count -eq 0) { Write-Host "   기기를 기다리는 중... (USB 연결, USB 디버깅 확인)" }
    else { Write-Host "   상태: $(($devices | ForEach-Object { "$($_.Serial)=$($_.State)" }) -join ', ') — 폰 화면을 확인하세요..." }
    Start-Sleep -Seconds 2
    $devices = Get-AdbDevices
}

if ($devices.Count -eq 0) {
    $samsung = @(Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | Where-Object { $_.FriendlyName -match 'SAMSUNG|Android|ADB' })
    $hints = @(
        '데이터 전송이 되는 USB 케이블인지 확인 (충전 전용 케이블은 안 됨)',
        '폰: 설정 > 개발자 옵션 > USB 디버깅 이 켜져 있는지 확인 (README §3.2)',
        '케이블을 뽑았다 다시 꽂고, 폰 알림의 USB 모드를 "파일 전송"으로 바꿔 보기'
    )
    if ($samsung.Count -eq 0) {
        $hints += 'Windows가 폰을 전혀 인식하지 못함 → Samsung USB 드라이버 설치: https://developer.samsung.com/android-usb-driver'
    } elseif (-not ($samsung | Where-Object { $_.FriendlyName -match 'ADB' })) {
        $hints += 'Windows는 폰을 보지만 ADB 인터페이스가 없음 → USB 디버깅이 꺼져 있거나 드라이버 문제'
    }
    Write-Bad 'adb에 연결된 기기가 없음' $hints
    exit 1
}

foreach ($d in $devices | Where-Object { $_.State -ne 'device' }) {
    switch ($d.State) {
        'unauthorized' { Write-Bad "$($d.Serial): 승인 안 됨" @('폰 화면의 "USB 디버깅을 허용하시겠습니까?"에서 "이 컴퓨터에서 항상 허용" 체크 후 허용', '창이 안 보이면: 개발자 옵션 > USB 디버깅 권한 승인 취소 → 케이블 재연결') }
        'offline' { Write-Bad "$($d.Serial): offline" @('케이블 재연결', 'adb kill-server; adb start-server') }
        default { Write-Bad "$($d.Serial): $($d.State)" @() }
    }
}

$ready = @($devices | Where-Object { $_.State -eq 'device' })
if ($ready.Count -eq 0) { exit 1 }
if ($Serial) {
    $pick = $ready | Where-Object { $_.Serial -eq $Serial }
    if (-not $pick) { Write-Bad "지정한 기기 $Serial 이(가) 연결되어 있지 않음" @("연결된 기기: $(($ready | ForEach-Object Serial) -join ', ')"); exit 1 }
} elseif ($ready.Count -gt 1) {
    Write-Bad '기기가 여러 대 연결됨' @("-Serial 로 하나를 고르세요: $(($ready | ForEach-Object { "$($_.Serial) ($($_.Model))" }) -join ', ')")
    exit 1
} else {
    $pick = $ready[0]
}
$script:deviceSerial = $pick.Serial
$env:ANDROID_SERIAL = $pick.Serial   # 아래 gradlew installDebug도 이 기기로
Write-Ok "연결됨: $($pick.Serial)"

# ---------------------------------------------------------------- 기기 정보

Write-Step '3. 기기 정보'
$model = Get-Prop 'ro.product.model'
$release = Get-Prop 'ro.build.version.release'
$sdk = Get-Prop 'ro.build.version.sdk'
$soc = Get-Prop 'ro.soc.model'
Write-Host "   모델 $model / Android $release (API $sdk) / SoC $soc"
if ($model -eq $ExpectedModel) { Write-Ok "기준 기기 $ExpectedModel" }
else { Write-Bad "기준 기기($ExpectedModel)가 아님: $model" @('다른 기기도 동작할 수 있지만 명세의 수치는 기준 기기 기준이다. ARCore Depth 지원 여부는 앱의 녹화 화면에서 확인') }

# ---------------------------------------------------------------- ARCore

Write-Step '4. ARCore (Google Play 서비스(AR))'
$arPkg = Invoke-Adb @('shell', 'pm', 'list', 'packages', $ArcoreId)
if ($arPkg -match "(?m)^package:$([regex]::Escape($ArcoreId))\r?$") {
    $ver = (Invoke-Adb @('shell', 'dumpsys', 'package', $ArcoreId)) -split "`n" | Where-Object { $_ -match 'versionName=' } | Select-Object -First 1
    Write-Ok "설치됨 $(($ver -replace '.*versionName=', '').Trim())"
} else {
    Write-Bad 'ARCore(Google Play 서비스(AR))가 설치되어 있지 않음' @('폰에서 Play 스토어 페이지를 열었다. "설치"를 누르세요', '앱을 처음 실행할 때도 설치 화면으로 안내된다')
    Invoke-Adb @('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-d', "market://details?id=$ArcoreId") | Out-Null
}

# ---------------------------------------------------------------- 저장 공간·배터리

Write-Step '5. 저장 공간·배터리'
$df = (Invoke-Adb @('shell', 'df', '-k', '/storage/emulated/0')) -split "`n" | Select-Object -Last 1
$cols = $df -split '\s+'
if ($cols.Count -ge 4 -and $cols[3] -match '^\d+$') {
    $freeGb = [math]::Round([double]$cols[3] / 1MB, 1)
    if ($freeGb -ge $MinFreeGb) { Write-Ok "여유 공간 $freeGb GB" }
    else { Write-Bad "여유 공간 $freeGb GB (< $MinFreeGb GB)" @('10분 녹화는 MP4만 약 1.2 GB. 세션 목록에서 오래된 세션을 PC로 옮긴 뒤 삭제') }
} else {
    Write-Host "   (df 출력 해석 실패) $df"
}
$battery = (Invoke-Adb @('shell', 'dumpsys', 'battery')) -split "`n" | Where-Object { $_ -match '^\s*level:' } | Select-Object -First 1
if ($battery) { Write-Host "   배터리 $(($battery -replace '.*level:', '').Trim())%" }

# ---------------------------------------------------------------- 앱

Write-Step '6. WalkAssist 앱'
if ($Install) {
    Write-Host "   .\gradlew.bat :app:installDebug (기기 $($pick.Serial))"
    Push-Location $RepoRoot
    try {
        & .\gradlew.bat :app:installDebug
        if ($LASTEXITCODE -ne 0) { Write-Bad 'installDebug 실패' @('위 Gradle 오류 확인. PC 준비는 tools\setup\setup-windows.ps1 -CheckOnly 로 점검'); exit 1 }
    } finally { Pop-Location }
    # 녹화 화면의 카메라 권한 창을 미리 해결 (사용자가 폰에서 거부했어도 다시 허용)
    Invoke-Adb @('shell', 'pm', 'grant', $AppId, 'android.permission.CAMERA') | Out-Null
}
$appPkg = Invoke-Adb @('shell', 'pm', 'list', 'packages', $AppId)
if ($appPkg -match "(?m)^package:$([regex]::Escape($AppId))\r?$") {
    $appVer = (Invoke-Adb @('shell', 'dumpsys', 'package', $AppId)) -split "`n" | Where-Object { $_ -match 'versionName=' } | Select-Object -First 1
    Write-Ok "설치됨 $(($appVer -replace '.*versionName=', '').Trim())"
    $sessions = Invoke-Adb @('shell', "ls $SessionsDir 2>/dev/null")
    $n = @($sessions -split "`n" | Where-Object { $_ -match '^\d{8}_\d{6}_' }).Count
    Write-Host "   기기의 녹화 세션 $n 개 ($SessionsDir)"
    if ($Launch) {
        Invoke-Adb @('shell', 'am', 'start', '-n', "$AppId/.ui.dev.DevHomeActivity") | Out-Null
        Write-Ok '앱 실행함 (개발 모드 홈)'
    }
} else {
    Write-Bad '앱이 설치되어 있지 않음' @('-Install 을 붙여 다시 실행')
}

# ---------------------------------------------------------------- 요약

Write-Step '요약'
if ($problems.Count) {
    Write-Host "확인할 항목 $($problems.Count)개:" -ForegroundColor Yellow
    $problems | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
Write-Host "기기 준비 완료 ($model, $($pick.Serial)). 로그 보기: adb logcat -s WalkAssist" -ForegroundColor Green
