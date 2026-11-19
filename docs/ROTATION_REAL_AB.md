# 실제 하네스 회전 촬영 A/B 비교

2026-11-19 비교는 최신 main의 네 회전 녹화와 하네스 실측값 위 0.20 m·뒤 0.10 m를 사용한다. `head.offsetFromCameraM`와 `heading.chestMount.headOffsetBodyM`에 같은 값을 넣는다. 녹화 당시 장착 방식·머리 오프셋이 기록되지 않았으므로 하네스 장착·장착 yaw 0°를 가정한 재생이다.

PowerShell에서 아래 명령 하나로 네 세션을 일곱 변형으로 모두 재생하고 결과 ZIP을 만든다. 출력 폴더는 매번 새로 만든다.

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\measurement\run-rotation-real.ps1
```

다른 환경에서는 `./gradlew :core:rotationRealReplay -Pout=core/build/rotation-real-new`로 같은 비교를 실행한다. 결과 `summary.csv`와 세션별 실행 로그에 설정·5cm 보정 사용 여부가 남는다. 근거리 STOP 변형에는 `near_stop_debug.csv`(ROI 점 수·칸 수·크기 통과 후보·누적 관측 수·출력 수)도 쓴다. 이번 비교의 [결과 기록](ROTATION_REAL_RESULT.md)을 함께 참고한다.

| 변형 | 진행 방향 | 근거리 STOP | 높이 겹침 보정 |
|---|---|---|---|
| movement | 기존 이동 방향 | OFF | OFF |
| fused | M42 카메라 결합, 기존 합성 비교값 2.3° | OFF | OFF |
| chest | 가슴 고정 방향 | OFF | OFF |
| near | 가슴 고정 방향 | ON | OFF |
| overlap | 가슴 고정 방향 | ON | ON |
| near_system_age | 가슴 고정 방향, 정보 나이 상한 진단 | ON | OFF |
| overlap_system_age | 가슴 고정 방향, 정보 나이 상한 진단 | ON | ON |

`*_system_age` 두 변형은 근거리 STOP의 `maxAgeMs`만 기존 시스템의 `policy.maxInfoAgeMs`로 맞춘 진단이다(현재 500 ms, 기본 근거리 프로파일은 150 ms). 그 외 근거리 문턱은 동일하다.

실행기는 `tools/measurement/rotation-ab`의 설정과 `sessions.csv`를 읽는다. 회전 구간은 `testdata/README.md`에 기재된 카메라 방향 변화 10~90% 구간(0.1초 단위 근사)이다. `firstStopFromTurnStartMs`는 이 구간 시작 이후 첫 STOP의 시각 차다. 물체가 통로에 들어온 시점부터의 지연은 아니다. `stopFromTurnStartS`와 `earlyStopFromTurnStartS`는 회전 시작부터 녹화 끝까지 STOP이 나온 블록의 합집합 시간이며, 음원 수로 중복 합산하지 않는다.

`cameraYawErrorOver5Fraction`은 블록이 사용한 자세의 카메라 정면과 추정 진행 방향 차이가 5°를 넘은 비율이다. 회전 시작 이후의 블록을 사용한다. 몸 방향 정답이 없어 카메라 정면을 대리 기준으로 사용하며, 독립적인 몸 방향 정확도 지표로 해석할 수 없다. `nearVsOverlapGuidanceEqual`은 near·overlap의 `guidance.csv` 전체 바이트 비교다.

앞 물체 장면 두 개의 RGB에는 글씨가 붙은 유리문이 보인다. 대조 두 개도 실내 장면이며 장애물 위치 정답 파일이 없다. 추가 STOP은 영상과 함께 확인하고 오경보 판정은 보류한다. 고정 처리 시간 10 ms는 PC 재생 가정이다. 기기 처리 시간·발열·실제 경고음 지연은 기기에서 측정한다.

아래는 이전 촬영 절차와 175cm·75kg 임시 설정을 사용한 비교 기록이다.

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
