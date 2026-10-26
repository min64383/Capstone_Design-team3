# 녹화 세션에서 등장·소실 효과음 듣기

```powershell
.\gradlew.bat :viewer:run "-Psession=20261003_130815_S01" "-PobjectCues=true"
```

세션 계산이 끝나면 `▶ 재생`을 누른다. 위 `등장·소실 효과음` 체크를 바꾸면 다시 계산한다.
설정 탭에 마지막 이벤트의 시각·ID·등장/소실·재생/생략 사유가 표시된다.
처음으로 돌아가거나 타임라인을 클릭하면 미리 혼합한 같은 소리가 화면 시각에 맞춰 재생된다.
기본은 효과음 꺼짐. 비교 화면은 A 소리를 재생한다.

- 추적 스냅샷의 ID가 신선하게 300 ms 유지되면 등장음, 목록에서 빠진 상태가 1000 ms 유지되면 소실음.
- 소실은 물체 제거/안전 선언이 아니라 추적 정보 소실이다. 지도에 남아 추적되는 물체는 카메라 밖으로 나가도 즉시 소실되지 않는다.
- ID 변경·군집 분할은 새 물체처럼 알릴 수 있다. 이번 변경은 추적기를 바꾸지 않는다.
- UNKNOWN/PAUSED/스냅샷 만료 시 효과음을 중단한다. 복귀 시 이미 있는 ID는 새 기준으로 잡아 한꺼번에 등장음을 내지 않는다.
- STOP 안내 중에는 효과음을 생략한다. 시스템 알림 뒤 1000 ms, 효과음 재생 중, 이전 시작 후 400 ms 안의 이벤트도 생략한다(나중에 몰아서 재생하지 않음).
- 같은 시각에 여러 이벤트가 있으면 등장 우선, 그 안에서는 ID 순서로 하나만 재생한다. 모든 판정은 CSV에 남는다.
- 이전에 청취한 650→1000 Hz/250 ms, 550→350 Hz/300 ms 사인 스윕을 같은 식으로 생성한다. 별도 WAV 복사 없이 실행하며 혼합 출력은 WAV로 저장할 수 있다.
- 효과음은 양쪽 귀에 동일한 비공간 알림이다. 물체 방향은 기존 연속음이 담당한다.

## 튜닝

`viewer/src/main/resources/object-cues.properties`를 수정하고 viewer를 다시 실행한다.
`appearHoldMs`, `lostHoldMs`, `cooldownMs`, `gain`과 각 음의 주파수·길이·페이드를 바꿀 수 있다.
기본 gain 0.5는 단독 청취 파일보다 작게 혼합하기 위한 값이다. 기존 연속음은 낮추지 않고 남은 샘플 여유 안에서 혼합한다.
이 설정은 오른쪽 알고리즘 JSON과 별개이며 Android 앱에는 적용되지 않는다.

## 저장

화면에서 `CSV + WAV 저장(재실행)` 체크 후 `재실행`을 누른다. 상태줄에 저장 경로가 나온다.
`audio.wav`는 효과음까지 포함한 화면 재생과 같은 PCM, `object-cues.csv`는 시간·ID·종류·PLAYED/생략 이유다.
기존 `sonification.csv`의 주파수/음원 행은 연속음 정보이고 outputRms/outputPeak는 효과음 포함 최종 출력이다.
`object-cue-settings.txt`에 해당 실행의 효과음 설정을 기록한다. 소리가 시작된 뒤 STOP/UNKNOWN이 되면 꼬리가 중단될 수 있다.

화면 없이도 저장할 수 있다:

```powershell
.\gradlew.bat :viewer:exportSonification "-Psession=testdata/sessions/20261003_130815_S01" "-PobjectCues=true"
```

## 검사

```powershell
.\gradlew.bat :core:test --tests "hearspace.core.synth.ObjectCuesTest"
.\gradlew.bat :viewer:test --tests "hearspace.viewer.ObjectCueReplayTest"
```
