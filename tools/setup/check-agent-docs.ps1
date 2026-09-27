<#
.SYNOPSIS
    에이전트 지침 동기화 확인: AGENTS.md(Codex)와 CLAUDE.md + .claude/rules/*.md(Claude)의 규칙 항목이 같은지 비교한다.

.DESCRIPTION
    저장소 루트에서 실행한다. 지침을 바꾼 PR에서는 반드시 실행한다 (README §4.7).

      powershell -ExecutionPolicy Bypass -File tools\setup\check-agent-docs.ps1

    비교 대상은 목록 항목(`- `로 시작하는 줄)과 본문 문장이다. 제목(#)과 HTML 주석, 규칙 파일의 frontmatter는 무시한다.
    같으면 종료 코드 0, 다르면 한쪽에만 있는 줄을 보여 주고 1.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Get-RuleLines([string]$path) {
    $text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    $text = $text -replace '(?s)^﻿?---\r?\n.*?\r?\n---\r?\n', ''   # frontmatter (.claude/rules의 paths:)
    $text = $text -replace '(?s)<!--.*?-->', ''                         # HTML 주석
    return @($text -split '\r?\n' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' -and -not $_.StartsWith('#') })
}

$claudeFiles = @(Join-Path $RepoRoot 'CLAUDE.md') + @(Get-ChildItem (Join-Path $RepoRoot '.claude\rules') -Filter *.md | Sort-Object Name | ForEach-Object FullName)
$agentsFile = Join-Path $RepoRoot 'AGENTS.md'

$claude = @($claudeFiles | ForEach-Object { Get-RuleLines $_ })
$agents = Get-RuleLines $agentsFile

$onlyClaude = @($claude | Where-Object { $agents -notcontains $_ })
$onlyAgents = @($agents | Where-Object { $claude -notcontains $_ })

Write-Host "Claude: $($claudeFiles.Count)개 파일 $($claude.Count)줄 / Codex: AGENTS.md $($agents.Count)줄"
if ($onlyClaude.Count -eq 0 -and $onlyAgents.Count -eq 0) {
    Write-Host '동기화됨' -ForegroundColor Green
    exit 0
}
if ($onlyClaude.Count) {
    Write-Host "`nCLAUDE.md·.claude/rules 에만 있음 → AGENTS.md에 추가:" -ForegroundColor Yellow
    $onlyClaude | ForEach-Object { Write-Host "  $_" }
}
if ($onlyAgents.Count) {
    Write-Host "`nAGENTS.md 에만 있음 → CLAUDE.md 또는 .claude/rules/<모듈>.md에 추가:" -ForegroundColor Yellow
    $onlyAgents | ForEach-Object { Write-Host "  $_" }
}
exit 1
