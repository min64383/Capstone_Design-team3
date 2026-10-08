# PC 음향 CSV·WAV·엑셀 비교

기준 main: 231732c426b14f38accc8932970c0eb43fa1eb11. 2026-10-07 거리/높이 분리 매핑 추가: DISTANCE_HEIGHT_AUDIO.md 참조. 이 기능은 PC 관측용이며 음향 정책은 바꾸지 않는다.

## 실행 (저장소 루트 PowerShell)

```powershell
.\gradlew.bat :viewer:exportSonification "-Psession=testdata/sessions/20261003_130815_S01"
```

기본으로 앱의 risk-continuous.override.json을 사용한다. 결과는 콘솔에 출력되는
`data/sonification/<세션ID>/<시각-랜덤ID>/`에 저장된다.

지정 경로/설정으로 A/B 실험:

```powershell
.\gradlew.bat :viewer:exportSonification "-Psession=testdata/sessions/20261003_130815_S01" "-Pout=data/sonification/runA"
.\gradlew.bat :viewer:exportSonification "-Psession=testdata/sessions/20261003_130815_S01" "-PoverridesFile=my-continuous.json" "-Pout=data/sonification/runB"
```

사용자 override에도 sonify.mode=RISK_CONTINUOUS가 있어야 한다. 기존 출력 폴더는 덮어쓰지 않으므로 새 이름을 사용한다.

## GUI에서 저장

```powershell
.\gradlew.bat :viewer:run "-Psession=testdata/sessions/20261003_130815_S01"
```

설정 탭에 다음을 입력한다(앱 후보 설정과 동일).

```json
{"sonify":{"mode":"RISK_CONTINUOUS","mapping":"DISTANCE_HEIGHT"},"policy":{"maxSources":2,"prioritizeStop":true}}
```

위쪽 `CSV + WAV 저장(재실행)`을 체크하고 재실행한다. 상태줄/터미널에 경로가 표시된다.
이때 저장한 WAV는 GUI가 재생하는 동일 렌더 결과를 동일한 16-bit 방식으로 변환한 것이다.
GUI는 앱 후보 설정으로 시작한다. 기본값 버튼으로 돌아간 `{}`는 PULSE이므로 내보내기를 켜면 설명 오류가 나온다. 설정을 바꿔 다시 실행한다.
오디오 장치 볼륨/드라이버 처리와 PC 스피커의 실제 음압은 WAV에 포함되지 않는다.

## 파일

- sonification.csv: 블록마다, 물체마다 한 행. 물체가 없거나 UNKNOWN이면 NO_SOURCE 한 행.
- audio.wav: 48kHz 기본 설정, 16-bit stereo. 실제 표본률은 설정을 사용.
- default-config.json / overrides.json: 이번 실행의 설정.
- run.txt: 세션 경로, 샘플 수, 시작 시각, 완료 상태. 마지막 status=complete를 확인.
- 실패 시 .partial 파일만 남을 수 있으며 완료 결과로 사용하지 않는다.

## 엑셀 자동 그래프 (Windows 데스크톱 Excel 필요)

```powershell
powershell -NoProfile -File .\tools\analysis\sonification-excel.ps1 -RunDir .\data\sonification\runA
```

sonification.xlsx가 같은 폴더에 생긴다. 물체별 source_<ID> 시트의 P열 오른쪽에
거리/접근속도/TTC/risk/pitch/gain/active·duck 그래프가 있다. source_mix는 전체 출력 RMS/peak.
기존 xlsx는 덮어쓰지 않는다. Excel이 없어도 CSV는 다른 스프레드시트 앱에서 읽을 수 있다.
수동으로는 CSV를 불러와 ID 필터 후 elapsedS를 X축, 관심 열을 Y축으로 분산형 차트를 만든다.

## CSV 의미

| 열 | 의미 |
|---|---|
| elapsedS, frameStart | WAV의 초, 해당 블록 시작 표본 프레임(0부터) |
| tBlockNs | 원래 파이프라인 가상 시각. WAV 시간과 기준점이 다름 |
| state, phase | 안내 상태; COMMAND / RELEASE / NO_SOURCE |
| obstacleId, distanceM, azimuthDeg, band, inCorridor | 렌더링에 사용한 명령. release/무음 때 빈 값 |
| closingMps | 상대 거리 감소 속도. 음수는 멀어짐 |
| ttcS, ttcFinite | 유효 접근 없으면 TTC 빈 칸/false. 0으로 치환하지 말 것 |
| risk, active | 위험도 제어값과 활성 상태. risk는 충돌 확률이 아님 |
| targetPitchHz, pitchHz | 목표 기본주파수 / 블록 끝 평활값 |
| targetGain, gain | duck 포함 목표 진폭 / 블록 끝 평활값. 공간화/마스터/리미터 전 |
| duckGain, ducked | STOP 우선순위 감쇠. 1은 감쇠 없음. TTS duck 아님 |
| outputRms, outputPeak | 최종 양자화된 전체 믹스의 블록 RMS/peak. 물체별 값이 아니며 같은 블록 행에 반복 |

pitch/gain은 블록 끝 값이므로 elapsedS 이후 한 블록(기본 256/48000=약5.33ms)의 변화가 반영된다.
active=false라도 release/합성곱 꼬리가 남을 수 있다. NO_SOURCE에서도 시스템 알림은 들릴 수 있다.
무음은 장애물 없음/안전을 뜻하지 않는다. PCM RMS는 실제 음압/사용자 체감 음량이 아니다.
객체별 파일은 모든 후보 장애물이 아니라 렌더러가 처리한 음원만 기록한다.
Excel은 TTC 빈 칸과 소스 없는 구간을 빈 값으로 둔다. long ns 열의 정밀도는 Excel에서
손실될 수 있으므로 그래프에는 elapsedS 사용(원본 CSV tBlockNs는 정확).

## 검증

```powershell
.\gradlew.bat :viewer:test --tests "hearspace.viewer.SonificationExportTest"
```

제공 환경에서 JDK17 + 임시 Kotlin 2.1.20 컴파일러로 core/viewer 전체 main 및 viewer test 소스 컴파일 성공.
합성 테스트 1개 통과: 기록 ON/OFF PCM 동일, 저장 WAV PCM 동일, 시간 정렬, duck, release,
UNKNOWN, 무한 TTC 빈 칸, 덮어쓰기 방지. 실제 S02 녹화 20261003_130815_S01 실행 성공:
1768블록, 452608프레임, 9.429333초, CSV 6864행. 원래 Gradle 9.8.0 다운로드는 환경 네트워크로
실패했으므로 저장소 원래 Kotlin 2.4.20/Gradle 조합의 빌드는 여기서 검증하지 못했다.
Windows Excel COM 스크립트는 이 Linux 환경에서 실행 검증하지 못했다.
동봉 예시 xlsx는 환경에 설치된 openpyxl로 같은 실제 CSV를 읽어 별도로 생성·재열기 검증했다.
프로젝트에 Python 라이브러리 의존성은 추가하지 않는다.
