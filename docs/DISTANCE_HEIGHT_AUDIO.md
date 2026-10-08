# 거리→음량 / 좌우→공간음향 / 높이→주파수

기준: GitHub main 231732c + 이전 sonification CSV 패치.
GitHub 원격 변경 없이 적용 가능한 패치/전체 변경 파일을 제공한다.

## 매핑

- 거리: 기존 수평 거리 distanceM을 사용한다. 기본 0.5m 이내 최대 진폭, 3.0m 이상 최소 진폭, 사이 선형 보간. 실제 재생 대상은 기존 WARN/STOP/SILENT 정책으로 먼저 정한다.
- 좌우: 기존 방위각/HRTF. 왼쪽은 왼쪽 귀 쪽, 오른쪽은 오른쪽 귀 쪽. 헤드폰에서 확인.
- 높이: 대표점의 월드 Y - 추정 머리 월드 Y. -1m → 250Hz, 0m → 500Hz, +1m → 1000Hz. 범위 밖은 끝값으로 제한한다.
- 높이는 고도각이 아니므로, 같은 높이의 물체에 수평으로 가까워져도 목표 pitch는 유지된다.
- 물체 전체 키나 상단 높이가 아니라 선택된 대표점의 높이다. 현재 repPoint 전략의 흔들림/물체 ID 변경은 음향에도 반영될 수 있다.
- gain은 생성기 진폭이며 실제 체감 크기나 음압과 같지 않다. HRTF/리미터/주파수 민감도/이어폰이 청취 크기에 영향을 준다.
- 접근속도/TTC/risk는 CSV에 계속 남긴다. 새 매핑에서는 risk로 pitch·gain·배음을 바꾸지 않는다. 기존 활성/해제, 정적 STOP 유지, STOP 우선순위 duck은 유지한다. 따라서 정지 WARN은 onceS 후 꺼질 수 있고, duck 중인 소스는 거리값 외 추가 감쇠가 있다.

## 적용

기존 sonification 패치를 적용한 프로젝트(현재 사용자 상태):

```powershell
git apply --check "C:\압축푼경로\distance-height-after-sonification.patch"
git apply "C:\압축푼경로\distance-height-after-sonification.patch"
```

CSV 패치가 없는 깨끗한 main 231732c에 처음 적용하는 경우만 `distance-height-from-main.patch`를 사용한다.
두 패치를 연속 적용하지 않는다. check가 실패하면 다른 로컬 변경이 있는지 비교한다.
files/에는 저장소 경로대로 전체 신규/수정 파일, CODE.md에는 이번 변경 코드 전문이 있다.

## 테스트 및 청취 파일 생성

프로젝트 루트에서:

```powershell
.\gradlew.bat :core:test --tests "hearspace.core.synth.DistanceHeightAudioTest"
.\gradlew.bat :viewer:test --tests "hearspace.viewer.SonificationExportTest"
```

청취 파일 위치: core/build/test-output/distance-height/

| 파일 | 순서 | 고정 조건 |
|---|---|---|
| 01-distance-far-mid-near.wav | 2.4m → 1.5m → 0.6m | 정면, 머리 높이(500Hz) |
| 02-direction-left-center-right.wav | -60° → 0° → +60° | 거리1.5m, 머리 높이 |
| 03-height-low-mid-high.wav | 머리보다 -1m → 0m → +1m | 거리1.5m, 정면 |

각 구간 약2초 + release 약0.5초. 이 합성 청취 예시에 한해서 onceS=3초로 늘린다.
실제 앱·녹화 재생의 활성 규칙은 바꾸지 않는다. 음원은 위험 경고 없이 단서를 구별하는 개발 비교용이다.

## 실제 녹화 재생과 CSV/WAV 저장

```powershell
.\gradlew.bat :viewer:exportSonification "-Psession=testdata/sessions/20261003_130815_S01"
```

-Pout 생략 시 새 폴더를 자동으로 만든다. 경로는 완료 로그에 표시된다.
기본 내보내기는 새 매핑을 사용한다. 저장 CSV 끝의 `mapping=DISTANCE_HEIGHT`를 확인한다.
heightDeltaM과 targetPitchHz/pitchHz를 함께 비교한다. 이전 열 순서는 유지했다.

GUI도 처음부터 앱과 같은 후보 override로 시작한다:

```powershell
.\gradlew.bat :viewer:run "-Psession=testdata/sessions/20261003_130815_S01"
```

`CSV + WAV 저장(재실행)` 체크 후 재실행. `기본값({})` 버튼은 여전히 과거 PULSE 기준선으로 돌아간다.
과거 저장한 변형을 불러오면 mapping이 없어서 LEGACY_RISK로 돌아갈 수 있다. 새 매핑 명시:

```json
{"sonify":{"mode":"RISK_CONTINUOUS","mapping":"DISTANCE_HEIGHT"},"policy":{"maxSources":2,"prioritizeStop":true}}
```

비교용 예전 연속음은 mapping=LEGACY_RISK로 설정한다.
Android는 수정된 assets를 포함해 APK를 다시 빌드/설치해야 반영된다(이미 설치된 APK는 바뀌지 않음).
단순 AudioTestActivity/SimpleBeepRenderer 비프 테스트는 별도 경로라 이 매핑 대상이 아니다.

## 엑셀

```powershell
powershell -NoProfile -File .\tools\analysis\sonification-excel.ps1 -RunDir "data\sonification\생성된세션폴더\실행폴더"
```

Windows 데스크톱 Excel 필요. 물체별 높이 그래프가 추가된다. 높이/Pitch를 같은 시간으로 비교한다.
기존 xlsx가 있는 폴더는 덮어쓰지 않는다. 새 실행 폴더에서 생성한다.

## 검증 범위

JDK17/임시 Kotlin2.1.20으로 core/viewer main 및 test 소스 전체 직접 컴파일 성공.
관련 자동 테스트27개 통과: 새 매핑7, 기존 연속음/설정/우선순위/CSV 내보내기 포함.
새 매핑7개: 거리-진폭 분리, 높이-주파수 분리·범위 제한, 접근속도 분리, 무효/만료 제거,
설정 범위 검증, 좌우 에너지, 합성 WAV 생성.
원래 Gradle/Kotlin2.4.20 빌드 및 Android 실기기·Windows Excel COM 실행은 여기서 검증하지 않았다.
실제 S02 녹화는 새로운 매핑으로 CSV/WAV 생성 후 표본 시간 및 목표 매핑 식을 점검했다.
