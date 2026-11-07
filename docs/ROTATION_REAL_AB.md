# 실제 하네스 회전 촬영 A/B 비교

동일 세션을 두 번 재생한다. A는 가슴 방향 보정, B는 같은 보정에 근거리 조기 STOP을 추가한다. 두 설정 모두 카메라에서 머리까지 위 40cm·뒤 15cm, 좌우 및 장착 방향 편차 0을 가정한다. 175cm·75kg 시나리오의 임시값이며 실측 전이다.

## 촬영본 선택

기존 자료에 하네스 착용 상태의 제자리 회전, 장애물 위치 기록, frames.csv·meta.json·원본 깊이 영상이 있으면 그대로 사용한다. 새로 찍는 경우 보조자가 촬영을 시작하고, 착용자는 눈을 뜬 채 이동 없이 회전한다. T01에서 5초 정지 → 약 1초 동안 90° 회전 → 3초 유지 → 복귀 → 3초 유지를 3회 반복한다. 회전 직후 정면 약 0.8~1m에 상자를 두고 거리와 회전 시각을 기록한다. 장애물을 치운 대조 촬영에서는 회전 중 벽이 정면 근거리로 들어오는지도 기록한다. 가능하면 외부 영상으로 몸 방향과 정답 구간을 확인한다.

Windows 프로젝트 폴더에서 기기 연결을 확인하고, 촬영 후 세션 전체를 가져온다.

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\setup\check-device.ps1
powershell -ExecutionPolicy Bypass -File .\tools\setup\pull-sessions.ps1 -Latest 3
```

## 재생

```powershell
$rotationSessionPath = Read-Host "실제 세션 폴더 경로"
$rotationRunName = Get-Date -Format "yyyyMMdd_HHmmss"
.\gradlew.bat :core:replay "-Psession=$rotationSessionPath" -PoverridesFile=tools/measurement/rotation-ab/assumed17575-a.json "-Pout=data/rotation-ab/$rotationRunName/A" -PslowMs=10
if ($LASTEXITCODE -ne 0) { throw "A 재생 실패" }
.\gradlew.bat :core:replay "-Psession=$rotationSessionPath" -PoverridesFile=tools/measurement/rotation-ab/assumed17575-b.json "-Pout=data/rotation-ab/$rotationRunName/B" -PslowMs=10
if ($LASTEXITCODE -ne 0) { throw "B 재생 실패" }
explorer ".\data\rotation-ab\$rotationRunName"
```

두 실행의 replay_done과 입력 경고를 확인한다. 같은 시각의 첫 STOP, 정답 구간 중 STOP이 나온 비율, 빈 구간 경고, 대상 ID 전환을 비교한다. 정답 시각이 없으면 출력 차이만 보고한다. 10ms는 재생에서 가정한 처리 시간이다. 실제 경고음 지연은 기기에서 따로 측정해야 한다.
