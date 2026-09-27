<#
.SYNOPSIS
    기기의 녹화 세션을 PC의 data/sessions/ 로 가져오고(검증 포함), 원하면 spike_check.py 분석까지 실행한다.

.DESCRIPTION
    저장소 루트에서 실행한다. 기기의 세션은 지우지 않는다(삭제는 앱의 세션 목록에서).

      powershell -ExecutionPolicy Bypass -File tools\setup\pull-sessions.ps1                        # 기기의 세션 목록만
      powershell -ExecutionPolicy Bypass -File tools\setup\pull-sessions.ps1 -Latest 1              # 가장 최근 세션 1개
      powershell -ExecutionPolicy Bypass -File tools\setup\pull-sessions.ps1 -Latest 3 -Analyze     # 최근 3개 + 분석
      powershell -ExecutionPolicy Bypass -File tools\setup\pull-sessions.ps1 -Id 20260926_050843_S01
      powershell -ExecutionPolicy Bypass -File tools\setup\pull-sessions.ps1 -All                   # 아직 안 가져온 것 전부

    이미 가져온 세션은 건너뛴다(-Force 로 다시 받기). 분석 결과는 화면과 data/sessions/<세션ID>/spike_check.txt 에 남는다.
#>
[CmdletBinding(DefaultParameterSetName = 'List')]
param(
    [Parameter(ParameterSetName = 'Latest')][ValidateRange(1, 1000)][int]$Latest = 0,
    [Parameter(ParameterSetName = 'Id')][string[]]$Id,
    [Parameter(ParameterSetName = 'All')][switch]$All,
    [switch]$Analyze,
    [switch]$Force,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$AppId = 'walkassist.app'
$RemoteRoot = "/storage/emulated/0/Android/data/$AppId/files/sessions"
$LocalRoot = Join-Path $RepoRoot 'data\sessions'
$VenvPy = Join-Path $RepoRoot '.venv\Scripts\python.exe'
$SpikeCheck = Join-Path $RepoRoot 'tools\analysis\spike_check.py'

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    foreach ($root in @($env:ANDROID_HOME, "$env:LOCALAPPDATA\Android\Sdk")) {
        if ($root -and (Test-Path "$root\platform-tools\adb.exe")) { $adb = "$root\platform-tools\adb.exe"; break }
    }
}
if (-not $adb) { throw 'adb를 찾지 못했습니다. tools\setup\setup-windows.ps1 을 먼저 실행하세요.' }

$ready = @(& $adb devices | Where-Object { $_ -match '^(\S+)\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
if ($Serial) { $ready = @($ready | Where-Object { $_ -eq $Serial }) }
if ($ready.Count -eq 0) { throw '연결된 기기가 없습니다. tools\setup\check-device.ps1 로 연결을 확인하세요.' }
if ($ready.Count -gt 1) { throw "기기가 여러 대입니다. -Serial 로 고르세요: $($ready -join ', ')" }
$dev = $ready[0]

function Invoke-Shell([string]$cmd) {
    return @(& $adb -s $dev shell $cmd | ForEach-Object { $_.TrimEnd("`r") })
}

# ---------------------------------------------------------------- 기기의 세션 목록

# 한 번의 셸 호출로 이름·크기(KB)·파일 수·정상 종료 여부(meta.json에 stats가 채워졌는지)를 받는다
$listCmd = "cd $RemoteRoot 2>/dev/null || exit 0; for d in */; do d=`${d%/}; " +
          "kb=`$(du -sk `"`$d`" | cut -f1); n=`$(find `"`$d`" -type f | wc -l); " +
          "if grep -q durationS `"`$d/meta.json`" 2>/dev/null; then c=1; else c=0; fi; echo `"`$d|`$kb|`$n|`$c`"; done"
$remote = @(Invoke-Shell $listCmd | Where-Object { $_ -match '^\d{8}_\d{6}_[^|]+\|' } | ForEach-Object {
    $f = $_ -split '\|'
    [pscustomobject]@{
        세션ID = $f[0]
        크기MB = [math]::Round([double]$f[1] / 1024, 1)
        파일수 = [int]$f[2]
        정상종료 = if ($f[3] -eq '1') { '예' } else { '아니오(녹화 중단?)' }
        PC에있음 = if (Test-Path (Join-Path $LocalRoot $f[0])) { '예' } else { '' }
    }
} | Sort-Object 세션ID)

Write-Host "기기 $dev 의 세션 $($remote.Count)개 ($RemoteRoot)" -ForegroundColor Cyan
if ($remote.Count -eq 0) { return }
$remote | Format-Table -AutoSize | Out-String -Width 200 | Write-Host

# ---------------------------------------------------------------- 가져올 대상

$targets = switch ($PSCmdlet.ParameterSetName) {
    'Latest' { @($remote | Select-Object -Last $Latest) }
    'Id' {
        foreach ($i in $Id) {
            $m = $remote | Where-Object { $_.세션ID -eq $i }
            if (-not $m) { throw "기기에 세션 $i 이(가) 없습니다" }
            $m
        }
    }
    'All' { $remote }
    default { @() }
}
if (-not $Force) {
    $skip = @($targets | Where-Object { $_.PC에있음 })
    foreach ($s in $skip) { Write-Host "건너뜀(이미 있음, 다시 받으려면 -Force): $($s.세션ID)" }
    $targets = @($targets | Where-Object { -not $_.PC에있음 })
}
if ($PSCmdlet.ParameterSetName -eq 'List') {
    Write-Host '가져오려면 -Latest <개수>, -Id <세션ID>, -All 중 하나를 붙이세요.'
    return
}

# ---------------------------------------------------------------- 가져오기와 검증

New-Item -ItemType Directory -Force $LocalRoot | Out-Null
$pulled = New-Object System.Collections.Generic.List[string]
foreach ($t in $targets) {
    $sid = $t.세션ID
    $local = Join-Path $LocalRoot $sid
    if ($t.정상종료 -ne '예') { Write-Host "주의: $sid 은(는) meta.json에 통계가 없다(녹화가 정상 종료되지 않음). 그래도 가져온다." -ForegroundColor Yellow }
    if ($Force -and (Test-Path $local)) { Remove-Item -Recurse -Force $local }
    Write-Host "`n가져오는 중: $sid ($($t.크기MB) MB, 파일 $($t.파일수)개)" -ForegroundColor Cyan
    & $adb -s $dev pull "$RemoteRoot/$sid" "$LocalRoot"
    if ($LASTEXITCODE -ne 0) { Write-Host "실패: $sid (adb pull 종료 코드 $LASTEXITCODE)" -ForegroundColor Red; continue }
    $nLocal = @(Get-ChildItem -Recurse -File $local).Count
    if ($nLocal -ne $t.파일수) {
        Write-Host "검증 실패: 파일 수 기기 $($t.파일수) / PC $nLocal" -ForegroundColor Red
        continue
    }
    Write-Host "검증 OK: 파일 $nLocal 개" -ForegroundColor Green
    $pulled.Add($sid)
}

# ---------------------------------------------------------------- 분석

if ($Analyze -and $pulled.Count) {
    if (-not (Test-Path $VenvPy)) { throw ".venv가 없습니다. tools\setup\setup-windows.ps1 을 먼저 실행하세요." }
    # 한글 출력이 깨지지 않도록 Python과 PowerShell 모두 UTF-8로
    $env:PYTHONIOENCODING = 'utf-8'
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    foreach ($sid in $pulled) {
        $local = Join-Path $LocalRoot $sid
        Write-Host "`n== spike_check: $sid" -ForegroundColor Cyan
        $out = & $VenvPy $SpikeCheck $local
        $out | Write-Host
        $out | Out-File -Encoding utf8 (Join-Path $local 'spike_check.txt')
    }
}
Write-Host "`n완료: $($pulled.Count)개 → $LocalRoot" -ForegroundColor Green
