# 데모 중계 임시 말 소리(M66 설계 3.8절): Windows 내장 한국어 음성으로 문장마다 48 kHz 16-bit 모노 WAV를 만든다.
# 파일 이름은 문장(UTF-8)의 SHA-1 + .wav(서버 RelayClips와 같은 규칙). 데모에는 쓰지 않는다(M67에서 S10 음성으로 바꿈).
# 쓰기: powershell -ExecutionPolicy Bypass -File tools\relay\make-temp-clips.ps1 [-List data\relay\clips\texts-needed.txt] [-Out data\relay\clips]
param(
    [string]$List = "data\relay\clips\texts-needed.txt",
    [string]$Out = "data\relay\clips"
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$voice = $synth.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -eq "ko-KR" } | Select-Object -First 1
if (-not $voice) { throw "no ko-KR voice installed" }
$synth.SelectVoice($voice.VoiceInfo.Name)
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(48000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$sha = [System.Security.Cryptography.SHA1]::Create()
$outDir = (New-Item -ItemType Directory -Force $Out).FullName
$n = 0
foreach ($text in [IO.File]::ReadAllLines((Resolve-Path $List), [Text.Encoding]::UTF8)) {
    if (-not $text.Trim()) { continue }
    $hash = -join ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($text)) | ForEach-Object { $_.ToString("x2") })
    $synth.SetOutputToWaveFile((Join-Path $outDir "$hash.wav"), $format)
    $synth.Speak($text)
    $n++
}
$synth.SetOutputToNull()
Write-Output "made $n clips in $outDir with $($voice.VoiceInfo.Name)"
