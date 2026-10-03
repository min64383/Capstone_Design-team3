<#
.SYNOPSIS
    HEARSPACE 개발 PC 준비(Windows 11): 도구 확인·설치, 환경 변수 설정, local.properties, 분석용 Python venv.

.DESCRIPTION
    여러 번 실행해도 안전하다(이미 있는 것은 건너뜀). 저장소 루트에서 실행한다.

      powershell -ExecutionPolicy Bypass -File tools\setup\setup-windows.ps1              # 확인 + 설치
      powershell -ExecutionPolicy Bypass -File tools\setup\setup-windows.ps1 -CheckOnly   # 확인만 (아무것도 바꾸지 않음)
      powershell -ExecutionPolicy Bypass -File tools\setup\setup-windows.ps1 -WithScrcpy  # 시연용 scrcpy도 설치
      powershell -ExecutionPolicy Bypass -File tools\setup\setup-windows.ps1 -RemoveEnv   # 관리 대상 환경 변수 제거(JAVA_HOME, ANDROID_HOME, PATH의 SDK 항목)

    환경 변수는 사용자 범위(HKCU\Environment)에만 쓴다. 바꾸기 전에 기존 값을
    %LOCALAPPDATA%\HEARSPACE\env-backup-<시각>.json 에 백업한다.
#>
[CmdletBinding()]
param(
    [switch]$CheckOnly,
    [switch]$WithScrcpy,
    [switch]$RemoveEnv,
    [string]$StudioDir = "$env:ProgramFiles\Android\Android Studio",
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # 5.1에서 진행 표시줄이 다운로드·압축 해제를 크게 늦춘다
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$JbrDir = Join-Path $StudioDir 'jbr'
$BackupDir = Join-Path $env:LOCALAPPDATA 'HEARSPACE'
$SdkRepoXml = 'https://dl.google.com/android/repository/repository2-3.xml'
$PythonVersion = '3.11'      # MVP_SPEC §3
$MinJavaMajor = 17           # AGP 9.4 요구 (DECISIONS.md)

$ManagedVars = @('JAVA_HOME', 'ANDROID_HOME')   # 백업·현재 창 반영 대상(common.ps1)
. (Join-Path $PSScriptRoot 'common.ps1')

function Get-SdkPathEntries {
    return @((Join-Path $SdkRoot 'platform-tools'), (Join-Path $SdkRoot 'cmdline-tools\latest\bin'))
}

# ---------------------------------------------------------------- Android SDK 설치 도구

function Get-CompileSdk {
    $gradle = Get-Content (Join-Path $RepoRoot 'app\build.gradle.kts') -Raw
    if ($gradle -match 'compileSdk\s*=\s*(\d+)') { return [int]$Matches[1] }
    throw 'app/build.gradle.kts에서 compileSdk를 찾지 못했습니다'
}

$script:sdkRepo = $null
function Get-SdkRepo {
    if (-not $script:sdkRepo) {
        Write-Info "SDK 저장소 목록 받는 중: $SdkRepoXml"
        $script:sdkRepo = [xml](Invoke-WebRequest -UseBasicParsing $SdkRepoXml).Content
    }
    return $script:sdkRepo
}

function Install-CmdlineTools {
    $repo = Get-SdkRepo
    $pkg = $repo.'sdk-repository'.remotePackage | Where-Object { $_.path -eq 'cmdline-tools;latest' } | Select-Object -First 1
    $archive = $pkg.archives.archive | Where-Object { $_.'host-os' -eq 'windows' } | Select-Object -First 1
    $url = 'https://dl.google.com/android/repository/' + $archive.complete.url
    $sha1 = $archive.complete.checksum.'#text'
    if (-not $sha1) { $sha1 = [string]$archive.complete.checksum }
    $tmp = Join-Path $env:TEMP ('hearspace-cmdline-tools-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force $tmp | Out-Null
    try {
        $zip = Join-Path $tmp 'cmdline-tools.zip'
        Write-Info "다운로드 (약 $([math]::Round([double]$archive.complete.size / 1MB)) MB): $url"
        # Windows PowerShell 5.1의 Invoke-WebRequest는 큰 파일에서 매우 느리므로 Windows 11 내장 curl.exe를 쓴다
        $curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
        if ($curl) {
            & $curl -L --fail --retry 3 -o $zip $url
            if ($LASTEXITCODE -ne 0) { throw "cmdline-tools 다운로드 실패 (curl 종료 코드 $LASTEXITCODE)" }
        } else {
            Invoke-WebRequest -UseBasicParsing $url -OutFile $zip
        }
        $actual = (Get-FileHash -Algorithm SHA1 $zip).Hash
        if ($sha1 -and $actual -ne $sha1.ToUpperInvariant()) { throw "cmdline-tools 체크섬 불일치 (기대 $sha1, 실제 $actual)" }
        Expand-Archive -Path $zip -DestinationPath $tmp -Force
        $dest = Join-Path $SdkRoot 'cmdline-tools\latest'
        if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
        New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
        Move-Item (Join-Path $tmp 'cmdline-tools') $dest   # zip 안의 최상위 폴더 이름은 cmdline-tools
    } finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
}

function Resolve-PlatformPackage([int]$api) {
    # 새 SDK 저장소는 platforms;android-37.0 처럼 소수 버전 이름을 쓴다. 안정판(beta 아님) 중 정확히 맞는 것을 고른다.
    $repo = Get-SdkRepo
    $paths = @($repo.'sdk-repository'.remotePackage | ForEach-Object { $_.path })
    foreach ($cand in @("platforms;android-$api", "platforms;android-$api.0")) {
        if ($paths -contains $cand) { return $cand }
    }
    throw "SDK 저장소에서 API $api 플랫폼 패키지를 찾지 못했습니다"
}

function Install-SdkPackages([string[]]$ids) {
    # $ids는 sdkmanager 표기(platforms;android-37.0).
    # cmdline-tools 23(2026-09)부터 sdkmanager는 폐기 예정이고 Android CLI(android.exe)로 넘긴다. android.exe가 있으면 직접 쓴다
    # (sdkmanager.bat은 cmd가 ';'에서 인자를 잘라 버림). Android CLI는 설치 중 라이선스 파일도 만든다.
    $bin = Join-Path $SdkRoot 'cmdline-tools\latest\bin'
    $env:JAVA_HOME = $JbrDir
    $cli = Join-Path $bin 'android.exe'
    if (Test-Path $cli) {
        & $cli --no-metrics "--sdk=$SdkRoot" sdk install @($ids | ForEach-Object { $_ -replace ';', '/' })
    } else {
        $sdkm = Join-Path $bin 'sdkmanager.bat'
        (1..50 | ForEach-Object { 'y' }) | & $sdkm "--sdk_root=$SdkRoot" --licenses | Out-Null
        & $sdkm "--sdk_root=$SdkRoot" @($ids | ForEach-Object { "`"$_`"" })
    }
    # 확인한 버전에서는 설치를 마친 뒤 종료 코드 0xC0000409로 끝나는 경우가 있었다.
    # 종료 코드는 믿지 않고, 호출한 쪽이 설치된 파일로 확인한다.
    if ($LASTEXITCODE -ne 0) { Write-Warn2 "SDK 도구 종료 코드 $LASTEXITCODE — 설치 결과를 파일로 확인한다" }
}

# ================================================================ -RemoveEnv

if ($RemoveEnv) {
    Write-Step '환경 변수 제거'
    Backup-UserEnv
    foreach ($n in $ManagedVars) {
        if ($null -ne (Get-UserEnvRaw $n)) { Set-UserEnvRaw $n $null; Write-Info "$n 삭제" }
    }
    [void](Update-UserPath -add @() -remove (Get-SdkPathEntries))
    Send-EnvChanged
    Write-Host "`n완료. 새 터미널부터 적용된다. 설치된 프로그램은 지우지 않았다(winget uninstall로 제거)." -ForegroundColor Green
    return
}

# ================================================================ 1. 운영체제

Write-Step '1. 운영체제·PowerShell·winget'
$os = Get-CimInstance Win32_OperatingSystem
$build = [int]$os.BuildNumber
if ($build -ge 22000) { Add-Result 'Windows' 'OK' "$($os.Caption) (빌드 $build)" }
else { Add-Result 'Windows' '경고' "Windows 11(빌드 22000+) 기준 문서. 현재 빌드 $build" }
Write-Info "$($os.Caption), 빌드 $build, PowerShell $($PSVersionTable.PSVersion)"
if (Test-Winget) { Add-Result 'winget' 'OK' '' } else { Add-Result 'winget' '없음' "Microsoft Store에서 'App Installer' 설치 필요" }

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

# ================================================================ 3. Android Studio (JBR)

Write-Step '3. Android Studio와 내장 JDK(JBR)'
if (-not (Test-Path (Join-Path $JbrDir 'bin\java.exe'))) {
    if ($CheckOnly) {
        Add-Result 'Android Studio' '없음' "winget install Google.AndroidStudio (기대 경로 $StudioDir)"
    } else {
        Install-WingetPackage 'Google.AndroidStudio' 'Android Studio'
        if (-not (Test-Path (Join-Path $JbrDir 'bin\java.exe'))) { throw "설치 후에도 $JbrDir\bin\java.exe 가 없습니다. -StudioDir 로 설치 경로를 지정하세요." }
        Add-Result 'Android Studio' '설치함' $StudioDir
    }
} else {
    Add-Result 'Android Studio' 'OK' $StudioDir
}
$javaMajor = Get-JavaMajor $JbrDir
if ($javaMajor) {
    Write-Info "JBR Java $javaMajor ($JbrDir)"
    if ($javaMajor -ge $MinJavaMajor) { Add-Result 'JDK (JBR)' 'OK' "Java $javaMajor" }
    else { Add-Result 'JDK (JBR)' '경고' "Java $javaMajor < $MinJavaMajor. Android Studio를 업데이트하세요" }
}

# ================================================================ 4. Android SDK

Write-Step '4. Android SDK'
$api = Get-CompileSdk
Write-Info "SDK 경로 $SdkRoot, 필요한 플랫폼 API $api (app/build.gradle.kts의 compileSdk)"
$sdkm = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
$hasPlatformTools = Test-Path (Join-Path $SdkRoot 'platform-tools\adb.exe')
$hasPlatform = [bool](Get-ChildItem (Join-Path $SdkRoot 'platforms') -Directory -Filter "android-$api*" -ErrorAction SilentlyContinue)

if ($CheckOnly) {
    Add-Result 'SDK cmdline-tools' ($(if (Test-Path $sdkm) { 'OK' } else { '없음' })) $sdkm
    Add-Result 'SDK platform-tools (adb)' ($(if ($hasPlatformTools) { 'OK' } else { '없음' })) ''
    Add-Result "SDK platform API $api" ($(if ($hasPlatform) { 'OK' } else { '없음' })) ''
} elseif (-not (Test-Path (Join-Path $JbrDir 'bin\java.exe'))) {
    Add-Result 'Android SDK' '건너뜀' 'JBR이 없어 sdkmanager를 실행할 수 없음'
} else {
    New-Item -ItemType Directory -Force $SdkRoot | Out-Null
    if (-not (Test-Path $sdkm)) {
        Install-CmdlineTools
        Add-Result 'SDK cmdline-tools' '설치함' 'cmdline-tools;latest'
    } else {
        Add-Result 'SDK cmdline-tools' 'OK' ''
    }
    $toInstall = @()
    if (-not $hasPlatformTools) { $toInstall += 'platform-tools' }
    if (-not $hasPlatform) { $toInstall += (Resolve-PlatformPackage $api) }
    if ($toInstall.Count) {
        Write-Info "설치: $($toInstall -join ', ')"
        Install-SdkPackages $toInstall
        $okTools = Test-Path (Join-Path $SdkRoot 'platform-tools\adb.exe')
        $okPlatform = [bool](Get-ChildItem (Join-Path $SdkRoot 'platforms') -Directory -Filter "android-$api*" -ErrorAction SilentlyContinue)
        if (-not ($okTools -and $okPlatform)) { throw "SDK 패키지 설치 실패 (platform-tools=$okTools, API $api=$okPlatform). Android Studio의 SDK Manager로 설치하세요." }
        Add-Result 'SDK 패키지' '설치함' ($toInstall -join ', ')
    } else {
        Add-Result 'SDK 패키지' 'OK' "platform-tools, API $api"
    }
    # build-tools는 AGP가 빌드할 때 필요한 버전을 자동으로 받는다(SDK의 licenses/ 폴더 필요 → 위 설치 때 생김)
}

# ================================================================ 5. 환경 변수

Write-Step '5. 환경 변수 (사용자 범위)'
$changed = $false
$changed = (Set-ManagedVar 'JAVA_HOME' $JbrDir) -or $changed
$changed = (Set-ManagedVar 'ANDROID_HOME' $SdkRoot) -or $changed

$legacy = Get-UserEnvRaw 'ANDROID_SDK_ROOT'
if ($legacy -and ((ConvertTo-NormalizedPath $legacy) -ne (ConvertTo-NormalizedPath $SdkRoot))) {
    Write-Warn2 "ANDROID_SDK_ROOT='$legacy' 가 ANDROID_HOME과 다릅니다. 두 값이 다르면 Gradle이 오류를 냅니다."
    Add-Result 'ANDROID_SDK_ROOT' '경고' "ANDROID_HOME과 다름: $legacy (지우거나 같은 값으로)"
}

$pathBefore = Get-PathEntries (Get-UserEnvRaw 'Path')
$pathWanted = Get-SdkPathEntries
$missing = @($pathWanted | Where-Object { $e = ConvertTo-NormalizedPath $_; -not ($pathBefore | Where-Object { (ConvertTo-NormalizedPath $_) -eq $e }) })
$dupCount = $pathBefore.Count - @($pathBefore | ForEach-Object { ConvertTo-NormalizedPath $_ } | Select-Object -Unique).Count
if ($CheckOnly) {
    if ($missing.Count) { Write-Warn2 "PATH에 없음: $($missing -join ', ')" }
    if ($dupCount) { Write-Warn2 "사용자 PATH에 중복 항목 $dupCount 개" }
    $state = if ($missing.Count -or $dupCount) { '변경 필요' } else { 'OK' }
    Add-Result '환경 변수' $state 'JAVA_HOME, ANDROID_HOME, PATH(platform-tools, cmdline-tools)'
} else {
    $changed = (Update-UserPath -add $pathWanted -remove @()) -or $changed
    if ($changed) {
        Send-EnvChanged
        Update-SessionPath
        Add-Result '환경 변수' '설정함' '새 터미널부터 적용 (이 창은 이미 반영됨)'
    } else {
        Add-Result '환경 변수' 'OK' ''
    }
}

# ================================================================ 6. local.properties

Write-Step '6. local.properties'
$lp = Join-Path $RepoRoot 'local.properties'
$wantLine = 'sdk.dir=' + ($SdkRoot -replace '\\', '\\' -replace ':', '\:')
$curLine = if (Test-Path $lp) { Get-Content $lp | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1 } else { $null }
# 표기(\\ 와 /)는 달라도 같은 폴더면 그대로 둔다
$curDir = if ($curLine) { ($curLine -replace '^sdk\.dir=', '' -replace '\\:', ':' -replace '\\\\', '\' -replace '/', '\') } else { $null }
if ($curDir -and ((ConvertTo-NormalizedPath $curDir) -eq (ConvertTo-NormalizedPath $SdkRoot))) {
    Add-Result 'local.properties' 'OK' $curLine
} elseif ($CheckOnly) {
    Add-Result 'local.properties' '변경 필요' "현재: $curLine"
} else {
    $other = if (Test-Path $lp) { @(Get-Content $lp | Where-Object { $_ -notmatch '^sdk\.dir=' }) } else { @() }
    # BOM 없는 UTF-8로 쓴다(BOM이 있으면 첫 줄 키가 무시됨 — DECISIONS.md M0 수정 참고)
    [System.IO.File]::WriteAllLines($lp, [string[]](@($wantLine) + $other), (New-Object System.Text.UTF8Encoding($false)))
    Add-Result 'local.properties' '설정함' $wantLine
}

# ================================================================ 7. Python (uv)

Write-Step "7. 분석 도구: uv, Python $PythonVersion, .venv"
Initialize-AnalysisEnv

# ================================================================ 8. scrcpy (선택)

Write-Step '8. scrcpy (선택, 시연용 화면 미러링)'
if (Get-Command scrcpy -ErrorAction SilentlyContinue) {
    Add-Result 'scrcpy (선택)' 'OK' ''
} elseif ($WithScrcpy -and -not $CheckOnly) {
    Install-WingetPackage 'Genymobile.scrcpy' 'scrcpy'
    Add-Result 'scrcpy (선택)' '설치함' ''
} else {
    Add-Result 'scrcpy (선택)' '없음' '-WithScrcpy 로 설치'
}

# ================================================================ 요약

Write-Step '요약'
$results | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
$bad = @($results | Where-Object { $_.상태 -in @('없음', '변경 필요') -and $_.항목 -notlike '*(선택)*' })
if ($bad.Count) {
    if ($CheckOnly) { Write-Host "준비되지 않은 항목이 있다. -CheckOnly 없이 다시 실행하면 설치·설정한다." -ForegroundColor Yellow }
    else { Write-Host "준비되지 않은 항목이 있다. 위 표를 확인하라." -ForegroundColor Yellow }
    exit 1
}
Write-Host "PC 준비 완료. 다음: .\gradlew.bat :core:test" -ForegroundColor Green
