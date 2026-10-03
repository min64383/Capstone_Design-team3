<#
.SYNOPSIS
    tools/setup 스크립트가 함께 쓰는 함수(결과 표, 사용자 환경 변수 백업·편집, winget 설치, JDK 버전, 분석용 Python venv).

.DESCRIPTION
    직접 실행하지 않는다. 각 스크립트가 변수를 정한 뒤 점 소싱으로 읽는다:
      . (Join-Path $PSScriptRoot 'common.ps1')
    함수가 쓰는 호출 쪽 변수: $CheckOnly, $BackupDir, $ManagedVars(백업·현재 창 반영 대상), $RepoRoot, $PythonVersion.
    환경 변수는 사용자 범위(HKCU\Environment)에만 쓰고, 바꾸기 전에 $BackupDir 에 백업한다.
#>

$results = New-Object System.Collections.Generic.List[object]
function Add-Result([string]$item, [string]$state, [string]$detail) {
    $results.Add([pscustomobject]@{ 항목 = $item; 상태 = $state; 내용 = $detail })
}
function Write-Step([string]$msg) { Write-Host "`n== $msg" -ForegroundColor Cyan }
function Write-Info([string]$msg) { Write-Host "   $msg" }
function Write-Warn2([string]$msg) { Write-Host "   ! $msg" -ForegroundColor Yellow }

# ---------------------------------------------------------------- 환경 변수 도구

$EnvKeyPath = 'Environment'

function Get-UserEnvRaw([string]$name) {
    # %VAR%를 풀지 않은 원문으로 읽는다(PATH에 %...%가 있어도 보존하기 위해)
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($EnvKeyPath, $false)
    try { return $key.GetValue($name, $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames) }
    finally { $key.Close() }
}

function Set-UserEnvRaw([string]$name, [string]$value) {
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($EnvKeyPath, $true)
    try {
        if ($null -eq $value) { $key.DeleteValue($name, $false); return }
        # PATH는 %VAR%를 쓸 수 있도록 REG_EXPAND_SZ로 유지한다([Environment]::SetEnvironmentVariable은 REG_SZ로 바꿔 버림)
        $kind = if ($name -eq 'Path' -or $value -match '%') { [Microsoft.Win32.RegistryValueKind]::ExpandString } else { [Microsoft.Win32.RegistryValueKind]::String }
        $key.SetValue($name, $value, $kind)
    } finally { $key.Close() }
}

function Send-EnvChanged {
    # 새로 여는 탐색기·터미널이 바뀐 환경 변수를 읽도록 WM_SETTINGCHANGE 방송
    if (-not ('HEARSPACE.NativeEnv' -as [type])) {
        Add-Type -Namespace HEARSPACE -Name NativeEnv -MemberDefinition @'
[DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
public static extern System.IntPtr SendMessageTimeout(System.IntPtr hWnd, uint Msg, System.UIntPtr wParam, string lParam, uint fuFlags, uint uTimeout, out System.UIntPtr lpdwResult);
'@
    }
    $r = [System.UIntPtr]::Zero
    [void][HEARSPACE.NativeEnv]::SendMessageTimeout([System.IntPtr]0xffff, 0x1A, [System.UIntPtr]::Zero, 'Environment', 2, 5000, [ref]$r)
}

$script:envBackedUp = $false
function Backup-UserEnv {
    if ($script:envBackedUp) { return }
    New-Item -ItemType Directory -Force $BackupDir | Out-Null
    $file = Join-Path $BackupDir ("env-backup-{0}.json" -f (Get-Date -Format 'yyyyMMdd_HHmmss'))
    $data = [ordered]@{}
    foreach ($n in @('Path', 'ANDROID_SDK_ROOT') + $ManagedVars) { $data[$n] = Get-UserEnvRaw $n }
    $data | ConvertTo-Json | Out-File -Encoding utf8 $file
    Write-Info "기존 사용자 환경 변수 백업: $file"
    $script:envBackedUp = $true
}

function Get-PathEntries([string]$raw) {
    if (-not $raw) { return @() }
    return @($raw -split ';' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
}

function ConvertTo-NormalizedPath([string]$p) {
    return [Environment]::ExpandEnvironmentVariables($p).TrimEnd('\').ToLowerInvariant()
}

function Update-UserPath([string[]]$add, [string[]]$remove) {
    $raw = Get-UserEnvRaw 'Path'
    $entries = Get-PathEntries $raw
    $removeSet = @($remove | ForEach-Object { ConvertTo-NormalizedPath $_ })
    $seen = @{}
    $out = New-Object System.Collections.Generic.List[string]
    foreach ($e in $entries) {
        $k = ConvertTo-NormalizedPath $e
        if ($removeSet -contains $k) { continue }
        if ($seen.ContainsKey($k)) { continue }   # 중복 제거
        $seen[$k] = $true
        $out.Add($e)
    }
    foreach ($a in $add) {
        $k = ConvertTo-NormalizedPath $a
        if (-not $seen.ContainsKey($k)) { $seen[$k] = $true; $out.Add($a) }
    }
    $new = ($out -join ';')
    if ($new -ne $raw) {
        Backup-UserEnv
        Set-UserEnvRaw 'Path' $new
        Write-Info "사용자 PATH 갱신 (항목 $($entries.Count) → $($out.Count))"
        return $true
    }
    return $false
}

function Set-ManagedVar([string]$name, [string]$value) {
    $cur = Get-UserEnvRaw $name
    if ($cur -eq $value) { Write-Info "$name = $value (그대로)"; return $false }
    if ($CheckOnly) { Write-Warn2 "$name 이(가) '$cur' → '$value' 로 바뀌어야 함"; return $false }
    Backup-UserEnv
    Set-UserEnvRaw $name $value
    Write-Info "$name = $value (설정함, 이전: '$cur')"
    return $true
}

function Update-SessionPath {
    # winget 설치 직후 현재 창에서도 새 PATH가 보이도록
    $machine = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $user = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = "$machine;$user"
    foreach ($n in $ManagedVars) {
        $v = [Environment]::GetEnvironmentVariable($n, 'User')
        if ($v) { Set-Item -Path "env:$n" -Value $v }
    }
}

# ---------------------------------------------------------------- 설치 도구

function Test-Winget {
    return [bool](Get-Command winget -ErrorAction SilentlyContinue)
}

function Install-WingetPackage([string]$id, [string]$label) {
    if (-not (Test-Winget)) { throw "winget이 없습니다. Microsoft Store에서 '앱 설치 관리자(App Installer)'를 설치한 뒤 다시 실행하세요." }
    Write-Info "winget으로 $label 설치 중 ($id) — 관리자 권한 확인 창이 뜨면 허용하세요"
    & winget install --id $id --exact --silent --accept-package-agreements --accept-source-agreements
    if ($LASTEXITCODE -ne 0) { throw "$label 설치 실패 (winget 종료 코드 $LASTEXITCODE)" }
    Update-SessionPath
}

function Get-JavaMajor([string]$jdkDir) {
    $release = Join-Path $jdkDir 'release'
    if (-not (Test-Path $release)) { return $null }
    $line = Select-String -Path $release -Pattern '^JAVA_VERSION="([0-9]+)' | Select-Object -First 1
    if ($line) { return [int]$line.Matches[0].Groups[1].Value }
    return $null
}

# ---------------------------------------------------------------- 분석용 Python (uv, .venv)

function Initialize-AnalysisEnv {
    if (-not (Get-Command uv -ErrorAction SilentlyContinue)) {
        if ($CheckOnly) { Add-Result 'uv' '없음' 'winget install astral-sh.uv' }
        else { Install-WingetPackage 'astral-sh.uv' 'uv'; Add-Result 'uv' '설치함' '' }
    } else {
        Add-Result 'uv' 'OK' (& uv --version)
    }
    $venvPy = Join-Path $RepoRoot '.venv\Scripts\python.exe'
    if (Get-Command uv -ErrorAction SilentlyContinue) {
        if ($CheckOnly) {
            $state = if (Test-Path $venvPy) { 'OK' } else { '없음' }
            Add-Result '.venv' $state $venvPy
        } else {
            Push-Location $RepoRoot
            try {
                if (-not (Test-Path $venvPy)) {
                    & uv python install $PythonVersion
                    if ($LASTEXITCODE -ne 0) { throw "uv python install 실패" }
                    & uv venv --python $PythonVersion .venv
                    if ($LASTEXITCODE -ne 0) { throw "uv venv 실패" }
                }
                & uv pip install --python $venvPy -r tools/analysis/requirements.txt
                if ($LASTEXITCODE -ne 0) { throw "분석 도구 의존성 설치 실패" }
                Add-Result '.venv' 'OK' ((& $venvPy --version) + ', requirements 설치됨')
            } finally { Pop-Location }
        }
    }
}
