# 가상 장애물·TTS 테스트

M61 음성 출력의 개발자 청취 도구다. HEARSPACE Dev의 **가상 장애물·TTS 테스트**에서 카메라 없이 실행한다.
실제 물체 인식 성능이나 실시간 보행 정책의 시험 결과와 구분한다.

## 확인 순서

1. 이어폰을 연결하고 대상을 정면 몸통 또는 머리 높이로 선택한다.
2. 접근 테스트를 시작한다. 기본 궤적은 3m → 2m → 1m, 총 18초다.
3. 2m 구간을 400ms 확인하면 일반 안내, 1m 구간을 200ms 확인하면 정지 안내를 각각 한 번 요청한다(기본 요청 시각 6.4초·13.2초).
4. 같은 조건에서 비언어음·TTS·발화 중 감쇠를 각각 켜고 꺼 비교한다. 발화 중 장애물 이득은 `audio.ttsObstacleGain`, TTS 음량은 `audio.ttsVolume`을 사용한다.
5. 반복 방지 테스트는 1.92~2.08m를 왕복해도 일반 안내가 한 번인지 확인한다.
6. 경로 밖 대상은 TTS와 장애물 음원이 나오지 않아야 한다. 이 무음은 실제 경로의 안전 판정이 아니다.
7. 정지·뒤로·홈으로 나가면 가상 입력과 발화를 취소한다. 화면에 돌아와 다시 시작할 수 있다.

## 구현

- `core/audio/VirtualScenario.kt`: 합성 궤적·확인 시간·중복 방지·재시도 상태. 순수 Kotlin이며 기존 `Speech`와 실시간 인식 경로는 변경하지 않는다.
- `app/.../dev/VirtualScenarioTestActivity.kt`: UI, 최신 관측 교체, AndroidTts·BinauralRenderer·BinauralOutput 연결.
- `app/src/main/assets/config/virtual-scenario.json`: 테스트 거리·시간·높이. 앱 기본 Config 형식에 키를 추가하지 않는다.
- 화면에서만 연속음·높이 매핑을 선택하고 `comparisonHoldS` 동안 정적 비교음을 유지한다. 앱에 저장된 설정은 변경하지 않는다.
- 화면 로그는 발화 요청과 TTS 엔진의 실제 시작·완료 콜백을 구분한다. 요청 접수 후 관측이 만료되면 최신 가상 관측으로 재시도한다.
- 오디오 스레드는 허용 나이가 지난 관측으로 소리를 만들지 않는다. 실제 기기의 출력 지연과 청취성은 이어폰으로 확인한다.

## 검증 명령

```powershell
.\gradlew.bat :core:test --tests "hearspace.core.synth.VirtualScenario*Test"
.\gradlew.bat :app:assembleDebug
```

SDK 없는 정책 검사에도 가상 발화 규칙 검사를 포함한다. 이 경로는 렌더러 검사를 제외한다.

```powershell
.\gradlew.bat -p tools/tts-tests test
```
