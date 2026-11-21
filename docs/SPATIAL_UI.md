# 사용자 모드 공간음향 표시

2026-11-21 사용자 요청. M45 화면 확장. 최신 main(e7439aa)과 1118tts(29f8cc2)에 앞서 제공한 Hearspace-Object-TTS 연결을 적용한 사용자 화면을 기준으로 한다.

## 표시

- A안 방향 파동과 화살표를 AudioCmd.azimuthDeg에 맞춰 회전한다. 오른쪽이 +이며 그림은 물체가 있는 방향을 가리킨다.
- 사람 그림의 머리·몸통·다리를 HeightClass.HEAD·BODY·FLOOR에 맞춰 강조하고 글자로도 표시한다.
- 거리 숫자·분류 이름·높이 글자는 흰색으로 표시한다. 파동·화살표·높이 강조·주의 문구에는 거리 색을 사용한다.
- 기본 표시는 거리 1m 이하 빨강, 1m 초과~2m 이하 주황, 2m 초과 노랑이다. STOP은 거리와 관계없이 빨강이며 기존 멈춤 고리와 "멈춤" 문구를 유지한다.
- 가까워지는 색 변경은 문턱에서 바로 허용한다. 멀어질 때는 문턱에 0.15m 여유를 두고, 일반 색 변경을 200ms 동안 전환한다. STOP 빨강은 즉시 적용한다.
- 화면은 실제 안내 명령 중 STOP 우선, 같은 구간에서는 가까운 물체 1개를 표시한다. 음원 선택과 위험 판단 자체는 바꾸지 않는다.
- 실제 TTS STARTED가 온 문장만 표시하고, 완료·중단·오류·화면 이탈 때 지운다. 이전 발화의 늦은 종료 콜백은 현재 문장을 지우지 않는다.
- 화면 전체 두 번 탭, 길게 누르기, 검은 배경, 기존 상태 전이·알림·TalkBack 동작을 유지한다.

## 설정

app/src/main/assets/config/spatial-ui.json을 사용한다. 숫자는 사용자 시안용 시험값이다. nearM·middleM·hysteresisM은 화면 색 기준이며 core의 policy.stopM·warnMaxM이나 경고 거리에는 영향을 주지 않는다.

| 키 | 기본값 | 의미 |
| --- | --- | --- |
| enabled | true | 파동·높이·추가 글자·TTS 표시 사용 |
| nearM | 1.0 | 가까운 거리 색의 상한(m) |
| middleM | 2.0 | 중간 거리 색의 상한(m) |
| hysteresisM | 0.15 | 멀어질 때 색이 흔들리는 것을 줄이는 여유(m) |
| frontHalfAngleDeg | 22.5 | 정면으로 읽는 방위 범위의 절반(도) |
| pulsePeriodMs | 1400 | 파동 밝기 주기(ms) |
| frameIntervalMs | 50 | 그림 갱신 간격(ms), 최대 약 20회/초 |
| colorTransitionMs | 200 | 일반 색 전환 시간(ms) |

색 코드는 res/values/spatial_colors.xml에 있다. enabled=false이면 추가 표시를 끄고 기존 화살표 형태를 사용한다. 설정은 사용자 모드를 새로 열 때 읽는다.

## 입력과 높이 의미

LivePipeline.lastObjectSpeech의 같은 블록 출력·스냅샷을 함께 읽고 물체 ID로 대응한다. 현재·과거 관측만 사용하고 기존 policy.maxInfoAgeMs를 넘는 관측, SILENT, UNKNOWN, PAUSED, 무효 거리·방위·ID는 물체 표시에서 제외한다. 좌표계 초기화·다른 물체·관측 소실 때 색 이력을 지운다.

분류 이름은 앞서 연결한 PERSON·VEHICLE·TREE·STRUCTURE를 사용하며 분류 근거가 없으면 장애물로 표시한다. 새 물체 분류기를 추가하지 않는다.

기존 HeightClassifier는 통로 안 점의 **최저 높이**를 분류한다. HEAD는 머리 높이, BODY는 몸통 높이, FLOOR는 발 근처로 표시한다. 따라서 FLOOR는 바닥부터 이어진 큰 물체에도 나올 수 있다. 이 그림은 물체 전체의 높이나 실제 사람 몸의 어느 부분인지를 새로 추정한 결과가 아니다. 머리·몸통·발과 파동 간격은 이 기존 분류를 시각화한다.

TTS 파형은 엔진 콜백에 맞춘 발화 상태 애니메이션이며 출력 PCM의 주파수 스펙트럼을 측정하지 않는다. 시스템에서 애니메이션이 꺼져 있으면 정적인 파동·막대를 그린다. 화면이 보이지 않거나 발화가 끝나면 해당 애니메이션 갱신을 이어가지 않는다.

## 검증

SpatialFeedbackTest 14개와 기존 UserScreenTest 10개, 총 24개가 별도 JVM에서 통과했다. 색 경계와 히스테리시스, STOP 우선, 물체 ID와 높이·종류 대응, 미래·만료 입력 제거, 기존 화면 상태, 발화 ID 대응, 실제 설정 파일 파싱을 검사했다.

수정 UI와 실제 AndroidTts를 가용 Android API jar에 대해 컴파일했다. 전체 앱의 기존 ARCore·실행 환경과 생성 R·일부 공통 도구는 이 검사에서 컴파일용 대역을 사용했다. 프로젝트의 compileSdk 37을 사용하는 전체 Gradle 빌드와 실제 기기의 화면 배치·프레임률·발열·음성 재생은 미검증이다.

프로젝트에서 실행할 검사:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "hearspace.app.ui.user.SpatialFeedbackTest" --tests "hearspace.app.ui.user.UserScreenTest"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
```

기기에서는 사용자 모드의 실제 안내를 시작한다. 왼쪽·오른쪽, 머리·몸통·바닥 높이, 1m·2m 문턱을 양방향으로 지나는 장면, 먼 STOP, 추적 만료, 일시정지와 재개, 화면 이탈, 시스템 애니메이션 끄기, 큰 글꼴과 가로 화면에서의 배치를 확인한다. 개발자 화면에는 이 표시를 추가하지 않는다.

관련 공식 API:
- https://developer.android.com/reference/android/graphics/Canvas
- https://developer.android.com/reference/android/animation/ValueAnimator#areAnimatorsEnabled()
