<#
.SYNOPSIS
    평가 GUI(viewer) 실행 준비(Windows 11): Git·JDK 확인·설치, JAVA_HOME 설정, 정답 세션 확인, viewer 테스트로 검증.
    Android 기기·Android Studio·Android SDK는 필요 없다(README §3.7).

.DESCRIPTION
    여러 번 실행해도 안전하다(이미 있는 것은 건너뜀). 저장소 루트에서 실행한다.

      powershell -ExecutionPolicy Bypass -File tools\setup\setup-viewer.ps1               # 확인 + 설치 + 검증
      powershell -ExecutionPolicy Bypass -File tools\setup\setup-viewer.ps1 -CheckOnly    # 확인만 (아무것도 바꾸지 않음)
      powershell -ExecutionPolicy Bypass -File tools\setup\setup-viewer.ps1 -WithPython   # 정밀 지표·비교표용 Python(.venv)도
      powershell -ExecutionPolicy Bypass -File tools\setup\setup-viewer.ps1 -SkipTest     # 마지막 검증(:viewer:test) 생략

    JDK 선택 순서: 사용자 JAVA_HOME → 시스템 JAVA_HOME → Android Studio 내장 JDK(JBR) → Program Files의 JDK →
    없으면 winget으로 Eclipse Temurin 25 설치. 쓸 수 있는(17 이상) JAVA_HOME이 이미 있으면 바꾸지 않는다.
    환경 변수는 사용자 범위(HKCU\Environment)에만 쓰고, 바꾸기 전에 %LOCALAPPDATA%\HEARSPACE\env-backup-<시각>.json 에 백업한다.
    처음 검증할 때 Gradle과 라이브러리를 내려받는다(인터넷 필요, 수 분).
#>
[CmdletBinding()]
param(
    [switch]$CheckOnly,
    [switch]$WithPython,
    [switch]$SkipTest
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$BackupDir = Join-Path $env:LOCALAPPDATA 'HEARSPACE'
$PythonVersion = '3.11'                          # MVP_SPEC §3
$MinJavaMajor = 17                               # viewer·core 바이트코드 대상(JVM 17)과 Gradle 실행 최소
$JdkPackage = 'EclipseAdoptium.Temurin.25.JDK'   # 이 프로젝트를 빌드해 온 JDK(Android Studio JBR 25)와 같은 주 버전
$ManagedVars = @('JAVA_HOME')                    # 백업·현재 창 반영 대상(common.ps1)
. (Join-Path $PSScriptRoot 'common.ps1')

function Find-Jdk {
    # 쓸 수 있는 JDK(bin\java.exe가 있고 주 버전 >= 17)를 우선순위대로 찾는다. 없으면 $null.
    $cands = New-Object System.Collections.Generic.List[object]
    foreach ($scope in @('User', 'Machine')) {
        $v = [Environment]::GetEnvironmentVariable('JAVA_HOME', $scope)
        if ($v) { $cands.Add([pscustomobject]@{ Dir = [Environment]::ExpandEnvironmentVariables($v); From = "$scope JAVA_HOME" }) }
    }
    $cands.Add([pscustomobject]@{ Dir = "$env:ProgramFiles\Android\Android Studio\jbr"; From = 'Android Studio JBR' })
    $roots = @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Microsoft", "$env:ProgramFiles\Java", "$env:ProgramFiles\Zulu")
    $found = foreach ($r in $roots) { Get-ChildItem $r -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^(jdk|zulu)' } }
    $found | Sort-Object { Get-JavaMajor $_.FullName } -Descending | ForEach-Object { $cands.Add([pscustomobject]@{ Dir = $_.FullName; From = 'Program Files' }) }
    foreach ($c in $cands) {
        if (-not (Test-Path (Join-Path $c.Dir 'bin\java.exe'))) { continue }
        $major = Get-JavaMajor $c.Dir
        if ($major -and $major -ge $MinJavaMajor) { return [pscustomobject]@{ Dir = $c.Dir.TrimEnd('\'); Major = $major; From = $c.From } }
        Write-Info "건너뜀: $($c.Dir) (Java $major < $MinJavaMajor, $($c.From))"
    }
    return $null
}

# ================================================================ 1. 운영체제

Write-Step '1. 운영체제·PowerShell·winget'
$os = Get-CimInstance Win32_OperatingSystem
$build = [int]$os.BuildNumber
if ($build -ge 22000) { Add-Result 'Windows' 'OK' "$($os.Caption) (빌드 $build)" }
else { Add-Result 'Windows' '경고' "Windows 11(빌드 22000+) 기준 문서. 현재 빌드 $build" }
Write-Info "$($os.Caption), 빌드 $build, PowerShell $($PSVersionTable.PSVersion)"
if (Test-Winget) { Add-Result 'winget' 'OK' '' } else { Add-Result 'winget' '경고' "없으면 자동 설치 불가. Microsoft Store에서 'App Installer' 설치" }

# ================================================================ 2. Git

Write-Step '2. Git'
if (Get-Command git -ErrorAction SilentlyContinue) {
    $v = (& git --version)
    Add-Result 'Git' 'OK' $v; Write-Info $v
} elseif ($CheckOnly) {
    Add-Result 'Git' '없음' 'winget install Git.Git'
} else {
    Install-WingetPackage 'Git.Git' 'Git'
    Add-Result 'Git' '설치함' (& git --version)
}

# ================================================================ 3. JDK

Write-Step "3. JDK ($MinJavaMajor 이상)"
$jdk = Find-Jdk
if (-not $jdk) {
    if ($CheckOnly) {
        Add-Result 'JDK' '없음' "winget install $JdkPackage"
    } else {
        Install-WingetPackage $JdkPackage 'JDK (Eclipse Temurin 25)'
        $jdk = Find-Jdk
        if (-not $jdk) { throw "설치 후에도 JDK를 찾지 못했습니다. $env:ProgramFiles\Eclipse Adoptium 아래를 확인하세요." }
        Add-Result 'JDK' '설치함' "Java $($jdk.Major) ($($jdk.Dir))"
    }
} else {
    Add-Result 'JDK' 'OK' "Java $($jdk.Major) ($($jdk.From): $($jdk.Dir))"
}
if ($jdk) { Write-Info "사용할 JDK: Java $($jdk.Major), $($jdk.Dir) ($($jdk.From))" }

# ================================================================ 4. JAVA_HOME

Write-Step '4. 환경 변수 JAVA_HOME (사용자 범위)'
if ($jdk) {
    $user = Get-UserEnvRaw 'JAVA_HOME'
    $userOk = $user -and ((ConvertTo-NormalizedPath $user) -eq (ConvertTo-NormalizedPath $jdk.Dir))
    # 사용자 값이 있으면 시스템 값보다 먼저 쓰이므로, 시스템 JAVA_HOME으로 충분한 것은 사용자 값이 없을 때뿐
    $machineOk = ($jdk.From -eq 'Machine JAVA_HOME') -and -not $user
    if ($userOk -or $machineOk) {
        Add-Result 'JAVA_HOME' 'OK' $jdk.Dir
    } elseif ($CheckOnly) {
        Add-Result 'JAVA_HOME' '변경 필요' "현재 '$user' → '$($jdk.Dir)'"
    } else {
        if (Set-ManagedVar 'JAVA_HOME' $jdk.Dir) { Send-EnvChanged }
        Add-Result 'JAVA_HOME' '설정함' "$($jdk.Dir) (새 터미널부터 적용, 이 창은 반영됨)"
    }
    $env:JAVA_HOME = $jdk.Dir   # 아래 검증과 이 창의 gradlew가 쓰도록
} else {
    Add-Result 'JAVA_HOME' '건너뜀' 'JDK가 없음'
}

# ================================================================ 5. 저장소 안 입력 파일

Write-Step '5. 정답 세션·HRTF·기본 설정 (git에 있는 파일)'
$sessions = @(Get-ChildItem (Join-Path $RepoRoot 'testdata\sessions') -Directory -ErrorAction SilentlyContinue)
$answered = @($sessions | Where-Object { Test-Path (Join-Path $_.FullName 'annotations\obstacles.json') })
if ($sessions.Count) { Add-Result '정답 세션' 'OK' "testdata/sessions $($sessions.Count)개 (정답 파일 $($answered.Count)개)" }
else { Add-Result '정답 세션' '없음' 'testdata/sessions 가 비었다. git pull 로 최신을 받는다' }
foreach ($f in @('app\src\main\assets\hrtf\sadie2_d1_48k.hrir', 'app\src\main\assets\config\default.json')) {
    $p = Join-Path $RepoRoot $f
    if (Test-Path $p) { Add-Result (Split-Path $f -Leaf) 'OK' '' } else { Add-Result (Split-Path $f -Leaf) '없음' "$f 가 없다. git pull" }
}

# ================================================================ 6. 소리 장치

Write-Step '6. 소리 장치 (헤드폰은 직접 확인)'
$sound = @(Get-CimInstance Win32_SoundDevice -ErrorAction SilentlyContinue | Where-Object { $_.Status -eq 'OK' })
if ($sound.Count) { Add-Result '소리 장치' 'OK' (($sound | Select-Object -First 2 | ForEach-Object { $_.Name }) -join ', ') }
else { Add-Result '소리 장치' '경고' '정상 소리 장치를 찾지 못함. 소리 없이 화면만 볼 수 있다' }
Write-Info 'Windows 기본 출력 장치를 헤드폰으로 두어야 공간 음향의 방향이 들린다'

# ================================================================ 7. Python (선택)

Write-Step "7. 분석 도구 (선택): uv, Python $PythonVersion, .venv"
if ($WithPython) {
    Initialize-AnalysisEnv
} else {
    Add-Result 'Python 분석 도구 (선택)' '건너뜀' '정밀 지표·비교표(sweep, report)가 필요하면 -WithPython'
}

# ================================================================ 8. 검증

Write-Step '8. 검증: ./gradlew :viewer:test (정답 세션 재생·소리·화면 그리기)'
if ($CheckOnly -or $SkipTest) {
    Add-Result 'viewer 테스트' '건너뜀' $(if ($CheckOnly) { '-CheckOnly' } else { '-SkipTest' })
} elseif (-not $jdk) {
    Add-Result 'viewer 테스트' '건너뜀' 'JDK가 없음'
} else {
    Write-Info '처음이면 Gradle과 라이브러리를 내려받는다(수 분)'
    Push-Location $RepoRoot
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # Gradle이 stderr에 쓰는 경고로 멈추지 않게 종료 코드로만 판단
    try {
        & (Join-Path $RepoRoot 'gradlew.bat') ':viewer:test' '--console=plain'
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
        Pop-Location
    }
    if ($code -eq 0) { Add-Result 'viewer 테스트' 'OK' ':core·:viewer 빌드, 정답 세션 재생 통과' }
    else { Add-Result 'viewer 테스트' '실패' "종료 코드 $code. 위 Gradle 출력 확인" }
}

# ================================================================ 요약

Write-Step '요약'
$results | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
$bad = @($results | Where-Object { $_.상태 -in @('없음', '변경 필요', '실패') -and $_.항목 -notlike '*(선택)*' })
if ($bad.Count) {
    if ($CheckOnly) { Write-Host '준비되지 않은 항목이 있다. -CheckOnly 없이 다시 실행하면 설치·설정한다.' -ForegroundColor Yellow }
    else { Write-Host '준비되지 않은 항목이 있다. 위 표를 확인하라.' -ForegroundColor Yellow }
    exit 1
}
Write-Host "viewer 준비 완료. 실행:`n  .\gradlew.bat :viewer:run -Psession=20261003_130815_S01" -ForegroundColor Green
