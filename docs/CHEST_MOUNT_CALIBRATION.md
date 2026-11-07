# 가슴 하네스 방향·위치 보정 (실험 옵션)

2026-11-07. 기준 main `70ab2b9aa3b3ff40c07b32c5b94226bddf736169`. `fix/chest-mount-calibration`.

## 지금 가능한 것과 대기 중인 것

방향 보정과 실측 위치를 한 설정으로 선택하는 실제 core 옵션이다. `FastPath.headingOverride`로 정답 방향을 주는 실험을 앱 기능으로 간주하지 않는다. 이번 옵션은 `Heading` 안에서 현재 카메라 자세를 사용하고, 앱의 기존 `LivePipeline`이 이 클래스를 그대로 사용한다. 새 Android/ARCore API·모델·의존성은 추가하지 않았다.

기본 `heading.chestMount=null`은 OFF이며 기존 손파지/M42 동작을 유지한다. 새 키가 없는 옛 녹화 설정도 OFF로 읽는다. 이 문서와 테스트의 숫자는 합성 장면 값이다. **사용자의 실제 하네스 치수를 아직 받지 못했으므로 실측 완료·기기용 보정 적용 완료를 뜻하지 않는다.**

## 원리와 한계

하네스를 고정하고 똑바로 선 상태에서 새 세션을 시작한다. 손에 든 상태에서 세션을 시작한 다음 가슴에 옮겨 다는 절차는 지원하지 않는다. 첫 usable TRACKING 자세의 카메라 정면을 수평 투영하고 측정한 좌우 장착 각도를 빼서 몸 정면을 만든 뒤, 그 벡터를 카메라 좌표에 저장한다. 이후 현재 카메라 회전으로 이 몸 정면 벡터를 월드에 옮기고 수평 투영한다. 미래 프레임·이동 방향·매 프레임 정답 몸 방향을 사용하지 않는다. 고정된 pitch/roll은 초기 카메라 로컬 보정에 포함된다.

시작 카메라 정면 또는 이후 보정된 몸 방향의 수평 길이가 `minHorizontalNorm` 미만이면 방향을 알 수 없다. 가슴 모드에서는 이때 FastPath가 UNKNOWN으로 들어가 기존 확인 불가/대기 알림 규칙을 사용하고 장애물 음원을 중단한다. 정보 만료·추적 상실·일시정지 규칙도 유지한다. 이동 기반 방식으로 몰래 돌아가거나 이전 방향을 확정값으로 재사용하지 않는다.

머리 위치는 같은 실측 `headOffsetBodyM`을 빠른 경로·느린 경로·재생 평가·Viewer·녹화 기본 표시에서 공통으로 사용한다. 기존 `head.offsetFromCameraM`은 손파지 기준선으로 남으며 가슴 모드일 때는 적용하지 않는다. 새 오프셋도 기존 HeadPose의 **몸 정면 기준 오른쪽·월드 위·앞** 좌표다. 카메라 C_cv의 아래(+Y)/앞(+Z) 벡터를 그대로 넣으면 안 된다.

몸을 세우고 수직축 주위로 회전하는 가정이다. 몸 숙임·머리 독립 회전·장착이 미끄러지는 경우의 위치를 측정하지 않는다. 머리 방향 센서가 추가된 것은 아니다. 안내 좌우 기준은 몸 정면이다. 장착을 바꾸면 치수를 다시 재고 새 세션을 시작해야 한다. AR 월드 RESET은 궤적을 지우되 카메라 로컬 장착 관계는 유지한다. 이 옵션은 기존 복셀을 앵커 기준으로 재정렬하지 않으므로 지도 좌표 보정 문제를 해결하지 않는다.

## 실제로 재야 할 네 값

폰을 실제 사용할 하네스에 고정하고 머리를 정면으로 둔다. 가능하면 한 사람이 착용하고 다른 사람이 줄자로 잰다. 원점은 **카메라 렌즈 중심 → 머리 중심(양쪽 귀 사이)**이다. 휴대폰 밑부분에서 재거나 대각선 거리 하나만 재지 않는다.

| 입력 | 단위·부호 | 측정 |
|---|---|---|
| `rightCm` | cm, 오른쪽 + / 왼쪽 − | 렌즈에 비해 머리 중심이 사용자 오른쪽에 있는 거리. 중앙 정렬을 확인했으면 0 |
| `upCm` | cm, 위 + | 머리 중심 높이에서 렌즈 높이를 뺀 수직 거리 |
| `backCm` | cm, 뒤 + | 머리 중심이 렌즈보다 뒤에 있는 수평 거리. 앞이면 음수 |
| `yawDeg` | 도, 오른쪽 + / 왼쪽 − | 몸 정면에 비해 카메라 광축이 수평으로 돌아간 각도. 정면 장착을 확인했을 때만 0 |

좌우 각도는 위에서 본 몸의 정면선과 카메라 시선의 수평 방향 사이 각이다. 아래로 숙인 각도를 yaw에 넣지 않는다. 먼저 카메라가 몸 정면을 향하도록 장착을 맞추면 yaw 측정이 쉬워진다. 추정값 0을 실측으로 표시하지 않는다. 소수점은 `50.5`처럼 점으로 입력한다.

프로그램은 이 값들이 실제로 측정됐는지 자동 인증하지 못한다. 네 값을 필수로 받고 단위·유한값·기본 방향 조건을 검증할 뿐이다. 실제 측정 편차와 반복 장착 오차도 기록해야 한다.

## 실측 설정 생성 (Windows PowerShell)

이 브랜치 코드를 받은 프로젝트 폴더에서 실행한다. 이미 생성한 파일을 덮어쓰지 않는다. 재측정 때는 `-Pout=...`에 새 이름을 준다.

```powershell
$mountRightCm = Read-Host "렌즈에서 머리 중심까지 오른쪽 거리 cm (왼쪽은 음수)"
$mountUpCm = Read-Host "렌즈에서 머리 중심까지 위 거리 cm"
$mountBackCm = Read-Host "렌즈에서 머리 중심까지 뒤 거리 cm"
$mountYawDeg = Read-Host "카메라가 몸 정면보다 오른쪽으로 돌아간 각도 도 (왼쪽은 음수)"
.\gradlew.bat :core:chestMountConfig "-PrightCm=$mountRightCm" "-PupCm=$mountUpCm" "-PbackCm=$mountBackCm" "-PyawDeg=$mountYawDeg"
```

출력은 `data/calibration/chest-mount.override.json`. cm를 m로 바꾸고 뒤 거리의 부호를 반전하여 `heading.chestMount.headOffsetBodyM`에 쓴다. 장착 yaw·오프셋·수평 길이 문턱을 함께 쓰며 M42 결합은 0으로 명시한다. 하나라도 없으면 생성하지 않는다. 실제 값의 누락을 기존 0.39m로 채우지 않는다.

문턱 원본은 `tools/measurement/chest-mount.parameters.json`의 `minHorizontalNorm=0.1`이다. 실험용 수평 투영 문턱이며 실기 유효 한계로 검증한 값은 아니다. 이 도구가 생성한 JSON을 앱·재생에서 똑같이 쓴다.

### 기존 기기 설정이 있는 경우

먼저 기존 파일을 PC의 새 백업 경로로 가져오고, 생성 명령에 아래 인수를 추가한다.

```powershell
adb pull /sdcard/Android/data/hearspace.app/files/config.override.json data/calibration/device-before-chest.json
```

```text
-PexistingOverride=data/calibration/device-before-chest.json
```

도구는 기존 키를 보존하고 이번 heading 방식만 갱신한다. 기존 파일이 없으면 병합 인수를 주지 않는다. 이 경로의 기기 파일을 새 파일로 교체하는 것은 기존 실험 설정 전체에 영향을 줄 수 있으므로 생성 결과에서 보존 여부를 확인한다. CLI는 PC 출력 파일을 자동 덮어쓰지 않는다.

## 앱에서 적용하는 순서

1. Android 개발 환경·SDK·USB 디버깅을 준비한 뒤 **이 브랜치의 앱**을 설치한다: `./gradlew :app:installDebug` (PowerShell은 `.\gradlew.bat :app:installDebug`). 옛 앱은 새 설정 키를 읽을 수 없다.
2. 설치된 앱을 한 번 열어 앱 외부 파일 폴더를 만든다. 기존 기기 override가 있으면 먼저 위처럼 백업·병합한다.
3. 앱을 완전히 종료하고 실측 설정을 복사한다.

```powershell
adb shell am force-stop hearspace.app
adb push data/calibration/chest-mount.override.json /sdcard/Android/data/hearspace.app/files/config.override.json
```

4. 하네스가 고정된 상태에서 몸을 세우고 앱을 새로 시작한다. 설정은 프로세스에서 캐시하므로 안내 화면만 다시 열어서는 새 값이 적용되지 않을 수 있다.
5. 90도·180도 회전 뒤 3초 정지, 회전 뒤 계속 걷기를 각각 기록한다. 사람·장애물 배치와 몸 정면 정답을 외부 관찰로 남긴다. 카메라 방향을 그대로 정답으로 복사하지 않는다. STOP 유지·첫 STOP·방위 기준 오차·지도 정합을 따로 비교한다.

되돌리기: 앱 종료 후 백업한 기존 override를 원래 경로에 복원한다. 기존 파일이 없었던 경우에는 새 override 파일을 제거하면 된다. 기본 JSON을 수정하거나 main에 직접 반영하지 않는다.

## PC 검증

```powershell
.\gradlew.bat :core:test --tests hearspace.core.synth.ChestMountCalibrationTest
.\gradlew.bat :core:chestGuidanceExperiment
```

이 실험은 이전 [PR #97](https://github.com/min64383/Capstone_Design-team3/pull/97)의 82개 대조 조건에 **production 모드 30조건**을 추가해 총 112조건·336구간을 비교한다. production 모드는 테스트용 방향 대입을 사용하지 않으며, 기존 손파지 오프셋을 일부러 0.39m로 남겨 새 공통 실측 오프셋이 실제로 쓰이는지 확인한다. 잡음·실패 반례·STOP 분모·가상 10ms 스케줄의 한계는 PR #97과 같다. 실제 오디오 블록·스피커·ARCore 기기는 실행하지 않는다.

## 이번 검증 결과

- Kotlin 2.4.20 CLI로 core production·기존 테스트·신규 테스트 전체를 컴파일. 기존 Gradle 테스트와 같은 JUnit 6.1.3·설정·HRIR·출력 경로를 지정해 **core 395/395 통과**. 신규 측정/방향/UNKNOWN 테스트는 9개이며, 이 수에 포함된다.
- Viewer main/test 컴파일 성공. 최초 실행 17개 중 15개 통과, 리소스가 빠져 실패한 ObjectCueReplayTest는 main resources를 classpath에 추가해 재실행하여 통과. 남은 Windows 구분자 세션 경로 테스트 1개는 Linux에서 실패하며 main의 수정하지 않은 Sessions.kt를 별도 컴파일해 같은 실패를 재현했다. 이번 변경으로 그 파일을 수정하지 않았다.
- 생성 도구를 합성 입력으로 직접 실행해 cm→m/부호 변환 및 기존 출력 파일 덮어쓰기 거부 확인. 기존 기기 override 보존·필수 치수 누락·비유한 값·부분 설정·M42 충돌도 테스트했다. 사용자 실제 치수로 생성한 파일은 아직 없다.
- 112조건·336구간 실행 완료. 기존 82조건의 모든 CSV 값이 PR #97 결과와 일치. production 30조건·90구간은 각 조건의 적절한 보정 비교군(CALIBRATED 또는 CALIBRATED_MEASURED)과 STOP·첫 경고·방위 기준 오차·머리 위치 지표가 모두 일치했다.
- 전방 대상이 있는 production 28조건의 마지막 구간 STOP 표본 비율은 모두 100%. 옆 대조 2조건은 정답 사건 분모가 없어 빈 값이다. 회전 도중 최초 감지 지연이나 실제 탐지 정확도 100%라는 뜻이 아니다. 기본 회전 조건 첫 STOP 표본 지연은 약 167~200ms로 남았다.
- 실제 앞 거리 0.10m·기존 0.39m 설정을 남긴 방향 보정 비교군의 마지막 STOP 0% 반례는, production의 공통 측정 오프셋으로 90·180도 모두 100%로 회복했다. 이 치수는 합성 조건이며 하네스 실측값이 아니다.
- 결과: [chest_mount_production_cases.csv](chest_mount_production_cases.csv), SHA-256 `7cc628463cf816c265148d5d2e9732664c43d11da4853a9e4426dec3f24a1885`. 프레임 로그는 실행 출력 `trace.csv`. 독립 JVM의 전체 112조건 반복은 이번 단계에서 추가하지 않았으며 이전 82조건 반복 검증과 위 대조 비교를 사용했다.
- `git diff --check` 통과. Gradle 작업·Android 앱 assemble은 Gradle 9.8 배포판 다운로드에서 `Network is unreachable`로 막혀 완료하지 못했다. 실기 설치·청취·회전 촬영·실측 적용은 미완료다.
