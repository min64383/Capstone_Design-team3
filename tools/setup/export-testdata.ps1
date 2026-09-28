<#
.SYNOPSIS
    data/sessions/ 의 녹화 세션을 git에 올릴 경량본으로 testdata/sessions/ 에 복사한다.

.DESCRIPTION
    저장소 루트에서 실행한다. PC에서 core를 돌리는 데 필요 없는 arcore.mp4(기기 재생 전용)와
    분석 결과(spike_check.txt 등)는 복사하지 않는다. 폴더 이름과 형식은 원본 그대로 둔다.

      powershell -ExecutionPolicy Bypass -File tools\setup\export-testdata.ps1 -Id 20260928_084542_S02
      powershell -ExecutionPolicy Bypass -File tools\setup\export-testdata.ps1 -Id 20260928_084542_S02,20260928_101025_S01 -NoRgb

    이미 있는 세션은 건너뛴다(-Force 로 다시 복사). 올리기 전에 testdata/README.md 의 세션 표에 한 줄 추가한다.
#>
param(
    [Parameter(Mandatory = $true)][string[]]$Id,
    [switch]$NoRgb,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$SrcRoot = Join-Path $RepoRoot 'data\sessions'
$DstRoot = Join-Path $RepoRoot 'testdata\sessions'
# 세션 형식(docs/FORMAT.md) 중 경량본에 넣는 것. arcore.mp4는 넣지 않는다
$Files = @('meta.json', 'frames.csv', 'device.csv')
$Dirs = @('depth', 'raw_depth', 'depth_conf', 'rgb', 'annotations')
if ($NoRgb) { $Dirs = $Dirs | Where-Object { $_ -ne 'rgb' } }
$WarnMb = 30
# -File 로 실행하면 쉼표 목록이 문자열 하나로 들어온다
$Id = $Id | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ }

foreach ($sid in $Id) {
    $src = Join-Path $SrcRoot $sid
    $dst = Join-Path $DstRoot $sid
    if (-not (Test-Path (Join-Path $src 'meta.json'))) { throw "세션이 없습니다: $src" }
    if (Test-Path $dst) {
        if (-not $Force) { Write-Host "[건너뜀] $sid (이미 있음, -Force 로 다시 복사)"; continue }
        Remove-Item -Recurse -Force $dst
    }

    $meta = Get-Content -Raw -Encoding UTF8 (Join-Path $src 'meta.json') | ConvertFrom-Json
    if ($null -eq $meta.stats) { Write-Warning "$sid 는 정상 종료되지 않은 세션입니다(meta.stats = null)" }

    New-Item -ItemType Directory -Force $dst | Out-Null
    foreach ($f in $Files) {
        $p = Join-Path $src $f
        if (Test-Path $p) { Copy-Item $p $dst }
    }
    foreach ($d in $Dirs) {
        $p = Join-Path $src $d
        if (Test-Path $p) { Copy-Item -Recurse $p $dst }
    }

    $bytes = (Get-ChildItem -Recurse -File $dst | Measure-Object -Sum Length).Sum
    $mb = [math]::Round($bytes / 1MB, 1)
    Write-Host "[복사] $sid  $($meta.formatVersion)  $mb MB -> testdata\sessions\$sid"
    if ($mb -gt $WarnMb) { Write-Warning "$sid 경량본이 $WarnMb MB를 넘습니다. git 이력에 영구히 남으므로 꼭 필요한지 확인하세요" }
}
