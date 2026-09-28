# WalkAssist MVP 구현 명세 v0.2 (Android 앱 우선)

> 3조 「시각 정보의 청각 변환을 활용한 시각장애인 보행 보조 서비스」 캡스톤디자인(1) MVP
> 문서 버전: v0.2.2 (2026-09-28, §14-6 PC 분석용 녹화 경량본 `testdata/` 허용) · v0.2.1 (2026-09-28, M1 스파이크 반영: 지연 구간 분리·자세 불연속 감지·깊이 나이 기준 — `docs/FORMAT.md`) · v0.2 (2026-09-25) · 이전 버전: v0.1 (Python PC 파이프라인안, 폐기)
> 근거 문서: 프로포절, 1차 멘토링 정리 보고서 v1.0, 기술 조사 보고서 v0.1, 서비스 기준 및 기술 명세 정리본 v0.2
>
> **이 문서를 읽는 Claude Code에게:** 이 문서는 구현의 단일 기준(source of truth)이다. 문서와 코드가 충돌하면 문서를 따르고, 문서가 틀렸다고 판단되면 구현을 멈추고 사용자에게 수정을 제안한다. `(가설)`로 표시된 값은 설정으로 빼서 바꿀 수 있게 만든다. ARCore·Android API의 정확한 이름과 동작은 **추측하지 말고 공식 문서나 공식 샘플로 확인**한 뒤 사용한다.

---

## 0. 한 줄 요약

Galaxy S10 5G(SM-G977N)에서 동작하는 Android 앱으로, ARCore의 자세와 깊이를 이용해 **월드 좌표에 고정된 로컬 3D 맵**을 만들고, 진행 통로 안의 가장 가까운 장애물을 **HRTF 공간음향**으로 이어폰에 전달한다. 핵심 알고리즘은 **순수 Kotlin 모듈(`core`)**로 분리해 Windows PC에서 합성 데이터로 테스트하고, 앱은 **실시간·녹화·재생** 세 모드로 동작한다.

### v0.1 대비 주요 변경

| 구분 | 변경 |
|---|---|
| 플랫폼 | Python PC 파이프라인 → **Android 앱이 곧 MVP**. Python은 로그 분석 도구와 `core` 알고리즘 프로토타입(`prototypes/`, Kotlin 이식 전제)에만 사용 |
| 구조 | `core`(순수 Kotlin, PC 테스트) + `app`(Android) + `tools/analysis`(Python) |
| 입력 | 별도 녹화 앱 → 앱 내장 **녹화 모드**(ARCore MP4 + 프레임 로그)와 **재생 모드** |
| 음향 | Python HRTF → Kotlin HRTF 합성곱 + `AudioTrack` 저지연 출력 |
| 설계 수정 | ① 시야 밖 복셀 비감쇠 ② 머리 높이 판정을 통로 내 부분으로 ③ 정답 정렬을 보행 궤적 기준으로 ④ 손–머리 오프셋 실측 ⑤ 지연 지표를 구간별로 분리 ⑥ 필수·선택 마일스톤 ⑦ 거리 구간 정의 통일 ⑧ 합성 장면 추가 |
| 추가 | 로그 형식 v0 → 스파이크 → **형식 v1 확정 관문**, UI 명세(사용자 모드·개발 모드·시연) |

---

## 1. 범위

### 1.1 MVP에 포함

| 항목 | 내용 |
|---|---|
| 실행 모드 | 실시간(카메라), 녹화(ARCore MP4 + 프레임 로그), 재생(녹화 MP4를 ARCore에 입력) |
| 자세·깊이 | ARCore 모션 트래킹, ARCore Depth API |
| 3D 맵 | 월드 좌표 로컬 복셀 점유 맵 + 물체 목록(추적) |
| 대표점 | 3방식(중심점 / 최근접 / 통로 내 최근접), 실행 중 전환 가능 |
| 안내 정책 | 진행 방향 추정, 통로 필터, 거리 구간과 히스테리시스, 안내 상태 기계, 정보 만료 |
| 음향 | HRTF 바이노럴(좌우 방위각 중심), 구간별 소리 패턴, 머리 높이 전용 음색, 상태 알림음, 진동 |
| UI | 사용자 모드(화면 없이 사용), 개발 모드(녹화·디버그 오버레이·실험 패널·로그 내보내기) |
| 검증 | 합성 장면 단위 테스트(PC), 오프라인 재생 테스트(PC), 로그 기반 지표 계산(Python) |

### 1.2 MVP에서 제외 (하지 말 것)

- 신호등·표지·문자 인식, 물체 종류·물성 인식
- 이동 물체 예측(정지 장애물 가정. 이동 물체의 맵 오염 방지는 구현)
- 자체 SLAM/VIO, 전역 지도, 루프 클로저
- 깊이 신경망 모델(선택 마일스톤 O2로만)
- 음성 합성 문장 안내(시작·종료 안내 등 고정 문구 녹음 파일 또는 시스템 TTS 1~2개는 허용)
- 네트워크·클라우드 API, 계정, 앱스토어 배포

---

## 2. 기준 시나리오와 설계 원칙

### 2.1 기준 시나리오 (서비스가 가장 잘 동작하는 조건)

- 기기: Galaxy S10 5G (국내판 SM-G977N, Exynos 9820, Android 12). 이 문서의 "S10"은 이 기기를 가리킨다
- 파지: 한 손으로 **가슴 앞 중앙**에 들고, 카메라는 진행 방향, 높이 약 1 m
- 사용자: 이어폰 착용(기준은 유선), 초속 약 1 m로 직진
- 환경: 실내, 정적 장애물
- 사용자 평가 시에는 평소 보행 수단을 유지하고 보조자가 동행한다(기준 시나리오는 성능 정의용 최선 조건).

### 2.2 반드시 지킬 설계 원칙

1. **인과적 처리.** 어떤 코드도 현재 시각 이후의 프레임·자세를 사용하지 않는다. 보간 대신 과거 값만 쓰는 평활·외삽을 쓴다.
2. **최신 값 전달.** 스레드 간 전달은 큐가 아니라 "최신 값 1개" 교체 방식이다. 느린 소비자는 오래된 값을 건너뛴다.
3. **두 경로 분리.** 빠른 경로(자세 → 음원 상대 위치, ARCore 프레임마다 ≈30 Hz)와 느린 경로(깊이 → 맵 → 물체, 목표 5~10 Hz)를 분리한다. 100 ms 제약은 빠른 경로 중 **앱이 ARCore 프레임을 받은 뒤부터**에 적용한다(촬영 → 앱 수신 구간은 ARCore 몫으로 별도 측정, §10.2).
4. **정보 만료.** 모든 데이터는 `tCaptureNs`를 가진다. 음향 단계에서 정보 나이가 허용치를 넘으면 해당 음원을 재생하지 않는다. 깊이의 `tCaptureNs`는 **깊이 이미지 자체의 타임스탬프**다(프레임 시각이 아님). ARCore는 멈춘 깊이를 예외 없이 반복해 돌려줄 수 있다(M1 F5 관찰).
5. **무음 ≠ 안전.** 무음을 "장애물 없음"으로 안내하거나 로그에 그렇게 기록하지 않는다. 확인 불가 상태는 전용 알림음과 진동으로 구분한다.
6. **시야 밖 ≠ 사라짐.** 카메라 시야를 벗어난 장애물은 맵에서 유지한다. 1 m 이내의 낮은 장애물은 시야 밖에 있는 것이 정상이다(§7.3).
7. **설정 주도.** 모든 임계값은 설정 객체에서 읽는다. 코드에 수치를 하드코딩하지 않는다.
8. **`core`의 순수성.** `core` 모듈은 Android·ARCore 의존성을 가지지 않는다. Android 타입은 `app`에서 `core` 타입으로 변환한다.

---

## 3. 개발 환경

| 항목 | 내용 |
|---|---|
| OS | Windows 11 |
| 코드 편집·에이전트 | VS Code + Claude Code |
| Android 빌드·디버깅 | Android Studio(SDK, 플랫폼 도구, 에뮬레이터, Logcat). 빌드는 터미널의 Gradle 래퍼로도 가능해야 함 |
| JDK | Android Gradle Plugin이 요구하는 버전 (현재 안정 버전 확인 후 `docs/DECISIONS.md`에 기록) |
| 언어·빌드 | Kotlin, Gradle Kotlin DSL, 버전 카탈로그(`libs.versions.toml`) |
| Android SDK | minSdk (가설) 26, S10(Android 12)에서 동작 필수, targetSdk는 현재 안정 버전 |
| ARCore | ARCore SDK for Android. Sceneform 등 폐기된 라이브러리는 사용하지 않음. 카메라 배경 렌더링은 공식 샘플(hello_ar 계열)의 OpenGL ES 방식을 참고 |
| 테스트 기기 | Galaxy S10 5G (SM-G977N) 1대 (USB 디버깅) |
| 분석 도구 | Python 3.11, `numpy`, `pandas`, `matplotlib` (`tools/analysis`) |
| 화면 미러링(시연) | scrcpy |

셸 명령은 PowerShell 기준으로 작성한다. 예: `./gradlew :core:test`, `./gradlew :app:installDebug`, `adb logcat -s WalkAssist`.

---

## 4. 저장소 구조

```
walkassist/
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── core/                                  # 순수 Kotlin(JVM) 라이브러리
│   └── src/
│       ├── main/kotlin/walkassist/core/
│       │   ├── types/        Types.kt, Config.kt
│       │   ├── geometry/     Geometry.kt, Quaternion.kt
│       │   ├── mapping/      Floor.kt, VoxelMap.kt
│       │   ├── tracking/     Cluster.kt, Tracker.kt, RepPoint.kt, HeightClass.kt
│       │   ├── guidance/     Heading.kt, Corridor.kt, Policy.kt, StateMachine.kt
│       │   ├── audio/        Hrtf.kt, Sounds.kt, BinauralRenderer.kt
│       │   ├── pipeline/     SlowPath.kt, FastPath.kt, Snapshot.kt
│       │   └── session/      SessionFormat.kt, SessionReader.kt   # 오프라인 재생용
│       └── test/kotlin/walkassist/core/
│           ├── synth/        SyntheticScene.kt, Scenes.kt         # 합성 장면 생성기
│           └── ...           각 모듈 테스트, OfflineReplayTest.kt
├── app/                                   # Android 앱
│   └── src/main/
│       ├── java/walkassist/app/
│       │   ├── ar/          ArSessionManager.kt, FrameAdapter.kt, Recorder.kt, Playback.kt
│       │   ├── runtime/     SlowPathWorker.kt, AudioOutput.kt, Haptics.kt, ThermalMonitor.kt, RunLogger.kt
│       │   ├── ui/user/     UserModeActivity.kt
│       │   ├── ui/dev/      DevHomeActivity.kt, RecordScreen.kt, DebugOverlay.kt, ExperimentPanel.kt, SessionList.kt
│       │   └── render/      배경 카메라·오버레이 렌더링
│       └── assets/          config/default.json, hrtf/(승인 후), sounds/
├── tools/analysis/                        # Python 분석 전용
│   ├── align.py, metrics.py, report.py, plots.py
│   └── requirements.txt
├── prototypes/<언어>/<모듈>/               # (선택) core 알고리즘 프로토타입. 최종 구현은 Kotlin core로 이식 (README §4.8)
└── docs/  MVP_SPEC.md(이 문서), DECISIONS.md, LICENSES.md, FORMAT.md
```

---

## 5. 좌표계 규약

| 좌표계 | 정의 |
|---|---|
| **월드 (W)** | ARCore 월드. 오른손 좌표계, **+Y = 위(중력 반대)**, 원점 = 세션 시작 위치. 수평 축 방향은 시작 시점 기기 방향에 따라 정해짐 |
| **카메라, ARCore 규약 (C_gl)** | +X 오른쪽, +Y 위, **−Z 방향을 봄** (ARCore 카메라 자세의 규약으로 알려져 있음 — 스파이크에서 확인) |
| **카메라, 내부 계산 규약 (C_cv)** | +X 오른쪽, **+Y 아래, +Z 앞**. 역투영은 이 규약으로 계산 |
| **머리 (H)** | 원점 = 카메라 위치 + 오프셋(§12의 `head.offsetFromCameraM`, 월드 수평 기준), +Z = 진행 방향(수평), +Y = 위. 방위각 θ는 +Z 기준 오른쪽이 양수 |

```
T_world_from_cv = T_world_from_gl · diag(1, −1, −1, 1)
```

- 변환은 `app/ar/FrameAdapter.kt`(실시간·재생)와 `core/session/SessionReader.kt`(오프라인)에서만 한다. 이후 모든 `core` 코드는 `C_cv`와 `W`만 다룬다.
- **이미지 방향:** ARCore CPU 이미지는 기기의 화면 방향과 무관한 센서 방향일 수 있다. 폰을 세로로 들어도 내부 파라미터와 이미지는 **센서 방향 그대로** 쓰고, 화면 표시에서만 회전한다. 스파이크(§8.3)에서 확인한다.
- **손–머리 오프셋:** 기준 파지(가슴 앞 중앙)에서 실측한 값을 설정한다. 폰이 몸 중심에서 옆으로 20 cm 벗어나면 1 m 앞 장애물의 방위각이 약 11° 틀어진다. 이 영향은 합성 장면 테스트로 확인한다(부록 B의 SC-09).

---

## 6. 데이터 계약 (`core/types/Types.kt`)

모두 불변 `data class`로 정의한다. 배열은 `FloatArray`/`ShortArray` 등 원시 배열을 사용한다(성능). 벡터·행렬은 `core/geometry`의 경량 타입을 쓴다.

```kotlin
enum class TrackingState { TRACKING, PAUSED, STOPPED }

data class Intrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float,
                      val width: Int, val height: Int)

data class PoseFrame(                       // 빠른 경로 입력 (ARCore 프레임마다)
    val tCaptureNs: Long,
    val tracking: TrackingState,
    val worldFromCam: Mat4                  // C_cv 규약
)

data class DepthFrame(                      // 느린 경로 입력
    val tCaptureNs: Long,                   // 깊이 이미지 자체의 Image.getTimestamp() (Frame 시각 아님, §2.2-4)
    val depthMm: ShortArray,                // 0 = 무효
    val confidence: ByteArray?,             // 0~255
    val K: Intrinsics,
    val worldFromCam: Mat4,                 // 깊이 시각의 자세
    val source: String                      // "arcore_depth" | "arcore_raw_depth" | "synthetic"
)

data class Obstacle(
    val id: Int,
    val repPointW: Vec3,                    // 선택된 방식의 대표점(월드)
    val repCandidatesW: Map<RepStrategy, Vec3>,  // 3방식 모두(디버그·비교용)
    val aabbMinW: Vec3, val aabbMaxW: Vec3,
    val heightClass: HeightClass,           // FLOOR | BODY | HEAD (§7.4)
    val inCorridor: Boolean,
    val confidence: Float,
    val lastSeenNs: Long,
    val nObservations: Int
)

data class ObstacleSnapshot(
    val tCaptureNs: Long,                   // 이 스냅샷을 만든 깊이의 촬영 시각
    val obstacles: List<Obstacle>,
    val floorY: Float?,
    val mapHealth: MapHealth                // OK | DEGRADED
)

data class AudioCmd(
    val obstacleId: Int,
    val azimuthDeg: Float,                  // 머리 기준, 오른쪽 +
    val distanceM: Float,
    val band: Band,                         // STOP | WARN | SILENT
    val sound: SoundKind,                   // FLOOR_PULSE | HEAD_TONE
    val infoAgeMs: Float
)

data class GuidanceOutput(                  // 음향 블록마다 계산
    val tBlockNs: Long,
    val state: GuidanceState,               // NORMAL | DEGRADED | UNKNOWN | PAUSED
    val commands: List<AudioCmd>,
    val headingDeg: Float,
    val alert: AlertKind?                   // 상태 전이 시 1회 알림
)
```

---

## 7. `core` 모듈 명세

### 7.1 기하 (`geometry/`)

- 역투영(C_cv): `X = (u − cx)·d / fx`, `Y = (v − cy)·d / fy`, `Z = d`. 무효 깊이는 건너뜀.
- `Mat4`, `Vec3`, 쿼터니언 ↔ 회전행렬, `glToCv`.
- `headRelative(pW, head: HeadPose) -> (azimuthDeg, horizontalDistM)`: 수평면 투영. 고도각은 소리로 표현하지 않는다.
- 역투영 시 **픽셀 서브샘플링 간격**을 설정으로 둔다(S10 연산 부담 조절용).

### 7.2 바닥 (`mapping/Floor.kt`)

- 기본: 월드 +Y가 위이므로 **높이 히스토그램**. 카메라보다 낮은 점에서 가장 밀도 높은 높이를 `floorY`로 추정하고 지수 평활한다(`floor.emaAlpha`). 한 번 찾은 뒤에는 직전 `floorY ± floor.searchBandM` 안에서만 찾는다(v0.2.1 해석).
- 바닥을 아직 모르면 복셀 맵을 갱신하지 않고 `mapHealth = DEGRADED`로 둔다(바닥 점이 장애물로 쌓이는 것 방지).
- 바닥 점: `|y − floorY| < floor.toleranceM`.
- 바닥보다 확실히 낮은 점(내려가는 단차 후보)은 삭제하지 말고 개수만 로그에 남긴다(MVP 안내 대상 아님).

### 7.3 로컬 복셀 맵 (`mapping/VoxelMap.kt`)

- 희소 해시 복셀(키 = 정수 복셀 좌표), 크기 `map.voxelSizeM` (가설 0.05).
- 복셀 상태: `hits`, `score`(0~1), `lastSeenNs`.
- **관측 갱신:** 새 깊이의 비바닥 점이 들어간 복셀은 `hits += 1`, `score` 증가.
- **빈 공간 감쇠 (시야 안에서만):** 현재 깊이 프레임의 시야(절두체) 안에 있고, 복셀 중심을 깊이 맵에 투영했을 때 **유효 깊이가 복셀보다 `map.freeMarginM` 이상 멀리 있는 경우**에만 `score`를 감쇠한다(= 그 자리가 비어 있음이 관측됨). 이동 물체의 흔적이 이렇게 지워진다.
- **시야 밖 복셀은 감쇠하지 않는다.** 다음 조건에서만 삭제한다.
  - 사용자 뒤쪽으로 `map.passedMarginM` (가설 1.0) 이상 지나감(진행 방향 기준)
  - 마지막 관측 후 `map.maxUnseenS` (가설 10) 경과
  - 사용자로부터 `map.radiusM` (가설 5.0) 밖
- 군집화 대상: `hits >= map.minHits` 이고 `score >= map.minScore`인 복셀.

### 7.4 군집·높이 분류·추적·대표점 (`tracking/`)

**군집 (`Cluster.kt`)**: 대상 복셀 중심에 대해 수평(XZ) 거리 기반 DBSCAN(`cluster.epsM`, `cluster.minSamples`). 외부 라이브러리 없이 격자 인접 탐색으로 구현한다.

**높이 분류 (`HeightClass.kt`) — v0.2 수정:** 군집 전체가 아니라 **통로(§7.5) 안에 들어온 부분**의 점으로 판정한다. 벽에 붙은 간판처럼 바닥까지 이어진 구조물의 돌출부를 놓치지 않기 위해서다.
- 통로 내 점의 최저 높이 ≥ `floorY + cluster.headMinM` (가설 1.2) → `HEAD`
- ≥ `floorY + cluster.bodyMinM` (가설 0.5) → `BODY`
- 그 외 → `FLOOR`
- 통로 내 점이 없으면 `inCorridor = false`

**추적 (`Tracker.kt`)**: 이전 물체와 현재 군집을 대표점 거리 최근접 매칭(`track.matchRadiusM`). 새 군집은 새 id. 대표점은 지수 이동 평균(`track.emaAlpha`)으로 평활(과거 값만 사용). 삭제 규칙은 §7.3의 복셀 삭제 규칙과 같은 기준을 따른다.

**대표점 (`RepPoint.kt`)** — 비교 실험 대상, 설정 `repPoint.strategy`:
- `CENTROID`: 군집 점의 중심
- `NEAREST`: 사용자(머리 수평 위치)에 가장 가까운 점
- `CORRIDOR_NEAREST` (기본): 통로 안 점 중 진행 방향 거리 최소인 점
- 세 방식을 **항상 모두 계산**해 `repCandidatesW`에 담고(디버그·비교), 안내에는 선택된 방식만 쓴다.

### 7.5 안내 정책 (`guidance/`)

**진행 방향 (`Heading.kt`)**
- 최근 `heading.windowS` (가설 1.0) 동안 카메라 수평 이동 벡터로 진행 방향을 추정하고 평활한다.
- 이동량이 `heading.minTravelM` 미만이면 직전 값을 유지. 초기값은 첫 `TRACKING` 프레임의 카메라 정면 수평 투영.
- 손목 회전(카메라 요)은 진행 방향에 즉시 반영하지 않는다.

**통로 (`Corridor.kt`)**: 진행 방향 기준, 폭 `corridor.widthM` (가설 0.8), 높이 바닥 위 0 ~ `corridor.heightM` (가설 2.0), 전방 길이 `corridor.lengthM` (가설 3.5). 뒤쪽으로 `corridor.behindM` (가설 0.2)까지 포함(바로 옆·발밑 처리용).

**거리 구간 (`Policy.kt`) — v0.2 통일안**

| 구간 | 진행 방향 거리 | 동작 |
|---|---|---|
| `STOP` | < 1.0 m | 즉시 정지 패턴 |
| `WARN` | 1.0 ~ 2.5 m | 거리에 따라 반복 주기가 빨라지는 경고 패턴 |
| `SILENT` | 2.5 ~ 3.0 m | 추적하지만 소리 없음 |
| 후보 제외 | > 3.0 m | 음원 후보에서 제외 |

- 경계에는 히스테리시스 `policy.hysteresisM` (가설 0.15).
- 동시 음원 수 `policy.maxSources` (기본 1): 통로 안 가장 가까운 물체부터.
- 정보 나이 = 현재 시각 − 스냅샷 `tCaptureNs`. `policy.maxInfoAgeMs` (가설 300) 초과 시 해당 음원 제외.
- 참고: 정리본 PDF 표 13의 거리 구간은 이 표로 갱신한다.

**상태 기계 (`StateMachine.kt`)**

| 상태 | 진입 조건 | 출력 |
|---|---|---|
| `NORMAL` | 추적 `TRACKING`, 정보 나이 정상 | 음원 재생 |
| `DEGRADED` | 정보 나이 > 허용치의 50%, 또는 맵 상태 `DEGRADED` | 음원 유지, 로그 경고 |
| `UNKNOWN` | 추적 `PAUSED`/`STOPPED`, 정보 만료, 또는 **자세 불연속**(아래) | **모든 장애물 음원 중단** + `UNKNOWN` 알림음 1회 + 긴 진동, 이후 `state.unknownRepeatS` 간격으로 짧은 알림 |
| `PAUSED` | 사용자 일시정지 | 무음, 정지음 1회 |
| 복귀 | `TRACKING`이 `state.recoverFrames` 연속(불연속 없이) | 맵 `score`를 `state.recoverScoreScale`배로 낮춘 뒤 `NORMAL`, 준비 완료음. 자세 불연속으로 들어간 경우는 **맵·추적기·진행 방향을 초기화**한 뒤 복귀 |

**자세 불연속 (v0.2.1 추가)**: ARCore는 추적 상태가 `TRACKING`인 채로 월드 좌표를 재정렬할 수 있다(M1 관찰: 한 프레임에 8.7 m·146°). 연속한 두 `PoseFrame` 사이의 카메라 이동 속도가 `state.maxSpeedMps` 또는 회전 각속도가 `state.maxAngularSpeedDps`를 넘으면 자세 불연속으로 보고 `UNKNOWN`으로 전환한다. 이전 월드 좌표로 만든 맵은 새 좌표와 맞지 않으므로 감쇠가 아니라 초기화한다.

### 7.6 음향 (`audio/`)

**HRTF (`Hrtf.kt`)**
- 공개 SOFA HRTF 데이터 중 **라이선스를 확인한 것**을 사용자에게 제안하고, 승인 후 수평면(고도 0°) HRIR만 추출해 앱 자산(`assets/hrtf/`)에 넣는다. SOFA 파싱은 PC에서 Python 스크립트(`tools/analysis/extract_hrir.py`)로 미리 해서, 앱에는 단순 바이너리(방위각 목록 + 좌우 HRIR)로 넣는다.
- 방위각 사이는 인접 두 HRIR 선형 보간 또는 최근접 + 교차 페이드.
- `docs/LICENSES.md`에 출처와 라이선스를 기록한다.

**소리 패턴 (`Sounds.kt`)** — 모두 가설, 설정으로 조정

| 소리 | 내용 |
|---|---|
| `FLOOR_PULSE` | 광대역 짧은 버스트(예: 30 ms 분홍 잡음 + 포락선). `WARN`에서는 2.5 m → 1.0 m로 갈수록 반복 주기 선형 단축, `STOP`에서는 매우 빠른 반복 + 음량 상승 |
| `HEAD_TONE` | 머리 높이 돌출 전용 음색(고역 강조 버스트). 높이는 소리의 고도가 아니라 음색으로 구분 |
| 상태 알림음 | 시작, 준비 완료, 정지, `UNKNOWN` — 비공간(머리 중앙) 짧은 음. 서로 쉽게 구분되도록 음높이·리듬을 다르게 |

**렌더러 (`BinauralRenderer.kt`)**
- 블록 단위(`audio.blockSize`, 가설 256 샘플 @ 48 kHz) 렌더링. **블록마다 최신 자세로 `AudioCmd`를 다시 계산**한다(빠른 경로).
- HRIR 변경 시 블록 내 교차 페이드, 출력 리미터.
- `core`에 두어 PC에서 방위각 스윕 WAV를 생성해 테스트한다.

### 7.7 파이프라인 (`pipeline/`)

- `SlowPath.process(depth: DepthFrame): ObstacleSnapshot` — 역투영 → 월드 변환 → 바닥 → 복셀 갱신 → 군집 → 높이 분류 → 추적 → 대표점. 순수 함수형 입력·출력 + 내부 상태(맵·추적기)만 가짐.
- `FastPath.compute(pose: PoseFrame, snapshot: ObstacleSnapshot?, nowNs: Long): GuidanceOutput` — 진행 방향, 머리 기준 방위각·거리, 구간, 상태 기계.
- 두 함수는 스레드를 모른다. 스레드 배치는 `app`의 책임이다.

### 7.8 합성 장면 생성기 (`test/synth/`)

- 기본 도형(바닥, 벽, 상자, 원기둥, 판)의 월드 배치 + 보행 궤적(속도 1 m/s, 카메라 높이 1 m, 손 흔들림: 걸음 주기 상하 진폭·요 진폭, 손–머리 오프셋)으로 `PoseFrame`과 `DepthFrame`을 **레이캐스팅으로 정확히** 생성한다.
- 잡음 옵션: 깊이 곱셈 잡음(%), 스케일 편향(%), 무효 픽셀 비율, 프레임 드롭, 자세 잡음, 추적 상실 구간, **추적 중 자세 점프**(월드 좌표 재정렬), **깊이 정지**(같은 깊이 반복). 기본값은 잡음 0.
- 정답(장애물 AABB, 유형)을 함께 반환한다.
- 장면 목록은 부록 B.

### 7.9 오프라인 재생 (`session/`)

- `SessionReader`: 녹화 세션 폴더(§8)의 프레임 로그와 깊이 파일을 읽어 `PoseFrame`/`DepthFrame` 스트림으로 제공한다.
- `OfflineReplayTest`: 가상 시계로 느린 경로 처리 시간을 설정값 또는 기록된 실측값으로 주입하며 `SlowPath`/`FastPath`를 실행하고, 앱과 같은 형식의 실행 로그(§10.1)를 PC에 저장한다. **같은 입력이면 항상 같은 결과**여야 한다. 대표점 3방식 비교 등 재현 가능한 실험은 이 경로로 한다.

---

## 8. 녹화 세션 형식과 스파이크

### 8.1 세션 폴더 형식 v0 (초안)

```
<앱 전용 외부 저장소>/sessions/<sessionId>/
├── meta.json                 # 기기, ARCore 버전, 해상도, 내부 파라미터, 규약, 장면 ID, 파지 오프셋
├── arcore.mp4                # ARCore Recording API 출력 (재생 모드 입력)
├── frames.csv                # ARCore 프레임마다: tNs, tracking, 자세(tx..qw, ARCore 규약 원본), depthFile, depthTNs
├── depth/000123.png          # uint16 mm, 0 = 무효 (저장 간격 설정 가능)
├── depth_conf/000123.png     # uint8 (가능한 경우)
├── rgb/000123.jpg            # 선택: 디버그·분석용 저해상도 이미지 (저장 간격 설정 가능)
├── run_log/                  # 녹화 중 실시간 파이프라인도 돌렸다면 그 실행 로그(§10.1)
└── annotations/obstacles.json  # 정답: PC에서 사람이 작성 (§10.3)
```

- 자세는 **ARCore 규약 원본**으로 저장하고, 변환은 읽는 쪽에서 한다(§5).
- 저장이 밀리면 깊이·이미지 파일만 건너뛰고, **`frames.csv`의 자세 행은 모든 프레임 기록**한다.
- 파일 쓰기는 백그라운드 스레드에서 한다.

### 8.2 PC로 가져오기

`adb pull <세션 경로> data/sessions/` (PowerShell). 경로는 앱의 로그 내보내기 화면에 표시한다.

### 8.3 스파이크 체크리스트 (M1에서 확인 → `docs/FORMAT.md`에 결과 기록)

| # | 확인 항목 | 확인 방법 |
|---|---|---|
| F1 | S10에서 Depth 모드 지원 여부 | 세션 설정 시 지원 확인 API 결과를 화면에 표시 |
| F2 | 카메라 자세의 규약과 종류(물리 카메라 기준인지 화면 방향 기준인지) | 폰을 알려진 방향으로 돌리며 로그 확인 |
| F3 | CPU 이미지 해상도·방향, 내부 파라미터 | 이미지 저장 + 체커보드나 알려진 물체로 확인 |
| F4 | 깊이 해상도·가로세로 비율, 깊이 내부 파라미터 환산 규칙 | 깊이 이미지 저장, 알려진 거리 평면으로 확인 |
| F5 | 깊이 타임스탬프와 갱신 빈도 | 로그의 `depthTNs` 분포 |
| F6 | 일반 깊이와 원시 깊이(신뢰도 포함)의 차이 | 두 종류 모두 저장해 비교 |
| F7 | S10의 저장 처리량(초당 깊이·이미지 저장 수), 발열 | 10분 녹화 |
| F8 | 재생 모드에서 녹화 MP4가 실시간 세션처럼 동작하는지 | 같은 세션 재생 2회 비교 |
| F9 | 에뮬레이터에서 재생 가능한지 (선택) | 에뮬레이터에 ARCore 설치 후 재생 |

### 8.4 관문 G1: 형식 v1 확정

F1~F7 결과를 반영해 `docs/FORMAT.md`에 **형식 v1**을 확정한다(F8 재생 모드 확인은 재생 모드를 구현하는 M7로 이월, v0.2.1). `core/session/SessionReader`와 합성 생성기를 v1에 맞춘 뒤 M5 이후의 실제 데이터 작업을 진행한다. F1이 불합격이면 작업을 멈추고 사용자와 깊이 소스 대안(O2)을 논의한다.

---

## 9. `app` 모듈 명세

### 9.1 스레드 배치

| 스레드 | 역할 |
|---|---|
| GL 렌더 스레드 | ARCore `update()`, 카메라 배경·오버레이 렌더링, `PoseFrame` 생성 → 최신 자세 슬롯 교체. 깊이 이미지 획득 후 `DepthFrame`을 최신 깊이 슬롯에 교체 (획득·복사만 하고 처리하지 않음) |
| 느린 경로 워커 | 최신 깊이 슬롯을 가져와 `SlowPath.process` → 최신 스냅샷 슬롯 교체. 처리 중 들어온 깊이는 덮어써짐 |
| 오디오 스레드 | `AudioTrack`(저지연 성능 모드) 쓰기 루프. 블록마다 최신 자세·스냅샷으로 `FastPath.compute` → `BinauralRenderer` |
| 저장 스레드 (녹화) | 파일 쓰기 전담 |
| UI 스레드 | 화면·입력·진동 |

- 슬롯 교체는 `AtomicReference` 수준으로 단순하게 구현한다.
- ARCore 이미지 객체는 획득한 스레드에서 즉시 복사 후 닫는다(리소스 누수 방지).

### 9.2 실행 모드

| 모드 | 입력 | 출력 |
|---|---|---|
| 실시간 | 카메라 | 음향 + (개발 모드) 오버레이, 실행 로그 |
| 녹화 | 카메라 | 세션 폴더(§8.1). 선택적으로 실시간 파이프라인 동시 실행 |
| 재생 | 세션의 `arcore.mp4` | 실시간과 같은 코드 경로로 음향·오버레이·실행 로그 |

### 9.3 부가 기능

- `ThermalMonitor`: 기기 발열 상태를 주기적으로 로그와 오버레이에 기록(API 지원 범위 확인).
- `Haptics`: 상태 전이별 진동 패턴(준비 완료: 짧게 1회, `UNKNOWN`: 길게, 정지: 짧게 2회 — 가설).
- `RunLogger`: §10.1 로그를 CSV로 기록.

### 9.4 오디오 출력

- `AudioTrack` 저지연 성능 모드, 48 kHz 스테레오 float 또는 16-bit.
- 출력 지연 추정값(`AudioTrack` 타임스탬프 정보)을 로그에 기록한다.
- 음성(TalkBack·시스템 TTS)이 나올 때 공간음향 음량을 잠시 낮춘다(오디오 포커스 처리).
- 출력 지연이 부족하게 측정되면 선택 마일스톤 O5에서 C++ 저지연 오디오 라이브러리를 검토한다.

---

## 10. 계측과 검증

### 10.1 실행 로그 (앱과 오프라인 재생이 같은 형식)

- `slow_path.csv`: `tCaptureNs, tStartNs, tDoneNs, nPoints, nVoxels, nObstacles, floorY, mapHealth`
- `guidance.csv` (오디오 블록마다 또는 N블록마다): `tBlockNs, poseTNs, snapshotTNs, state, obstacleId, azimuthDeg, distanceM, band, sound, infoAgeMs, headingDeg`
- `obstacles.csv` (스냅샷마다): `tCaptureNs, id, heightClass, inCorridor, repStrategy별 대표점 좌표, aabb`
- `device.csv`: `tNs, thermalStatus, batteryPct, audioOutputLatencyMs`
- 모든 시각은 같은 단조 시계 기준.

### 10.2 지표 (`tools/analysis/metrics.py`) — 기준값은 가설

| 지표 | 계산 | 기준 (가설) |
|---|---|---|
| 방향 오차 95퍼센타일 | 정답 장애물의 통로 내 최근접점 방위각 vs 음원 방위각 | ≤ 6° |
| 경고 구간 과대추정 95퍼센타일 | 1.5~2.5 m에서 (추정 거리 − 정답 거리)/정답 거리, 양수만 | ≤ 10% |
| 거리 구간 일치율 | 추정 구간 vs 정답 구간 | 측정 후 설정 |
| 유형별 탐지율 | 통로 안에 들어온 정답 장애물이 음원으로 선택된 비율 (특히 `HEAD`, 얇은 기둥) | 측정 후 설정 |
| 머리 높이 분류 정확도 | 정답 `head_protrusion`이 `HEAD`로 분류된 비율 | 측정 후 설정 |
| 경고 시점 오차 | 정답 거리가 2.5 m를 지난 시각 대비 `WARN` 시작 시각의 차(초, 늦으면 양수) | 측정 후 설정 |
| 즉시 정지 누락 | 정답 거리 1.0 m 이내에 들어왔는데 `STOP`이 없던 장애물 수 (시야 밖 보존 확인) | 0 목표 |
| 음원 흔들림 | 정지 장애물 방위각의 블록 간 변화 표준편차 | 측정 후 설정 |
| 느린 경로 주기·정보 나이 | `slow_path.csv`, `guidance.csv` | 5~10 Hz, 정보 나이 ≤ 300 ms |
| 지연 (구간별) | (a0) ARCore: 촬영 시각(`tNs`) → 앱 수신 (a) 파이프라인: 앱 수신 → 오디오 블록 반영 (b) 출력 버퍼: `AudioTrack` 추정값 (c) 이어폰: 외부 측정 | (a)+(b) ≤ 100 ms. (a0)는 측정·보고만(M1 실측 125~170 ms, 제어 불가 — O3 착수 근거), (c)는 별도 표기 |
| 확인 불가 전환 시간 | 추적 상실·자세 불연속·깊이 정지 → `UNKNOWN` | 측정 후 설정 |
| 지속 동작 | 10분 실행 중 느린 경로 주기·발열 | 주기 기준 유지 |

### 10.3 정답 정렬 (`tools/analysis/align.py`) — v0.2 수정

- 정답 좌표계: 바닥의 시작 표시가 원점, 테이프 보행선 방향이 +Z, +Y 위.
- 정렬: 녹화 세션의 **카메라 궤적(수평)의 처음 `align.fitLengthM` (가설 2.0 m) 구간에 직선을 맞춰** 그 방향을 +Z로 둔다. 카메라 정면 방향은 쓰지 않는다(손 파지 각도 오차 제거).
- 원점: 궤적 시작점의 바닥 투영.
- 직선 적합 잔차와 정렬 각도 불확실성을 지표와 함께 보고한다.

---

## 11. UI 명세

### 11.1 원칙

- **사용자 모드와 개발 모드를 분리**한다. 두 모드는 같은 `core` 상태를 다르게 보여 줄 뿐이다.
- UI는 `app`의 얇은 층이며 안내 로직을 포함하지 않는다.

### 11.2 사용자 모드 (화면을 보지 않고 사용)

| 원칙 | 구현 |
|---|---|
| 소리·진동 우선 | 모든 상태 전이를 알림음 + 진동으로 알림 (§7.6, §9.3) |
| TalkBack 호환 | 화면 전체가 하나의 버튼. 모든 요소에 음성 설명. TalkBack 사용 시 "아무 데나 두 번 탭"으로 동작 |
| 한 손 조작 | 두 번 탭(시작·일시정지 전환), 길게 누르기(종료) |
| 저시력 고려 | 검은 배경, 고대비 대형 글자로 상태 1줄 + 가장 가까운 장애물 방향 화살표 |
| 배터리 | 화면 밝기 최소, 화면 꺼짐 방지 |

흐름: 실행 → 두 번 탭 → 시작 안내(1회) → 추적 준비 중 대기음 → 준비 완료음·진동 → 안내 → 두 번 탭으로 일시정지·재개 → 길게 눌러 종료.
이 흐름과 알림음·진동 패턴은 **가설**이며 사용자 평가에서 검증한다.

### 11.3 개발 모드

진입: 별도 런처 아이콘(권장) 또는 사용자 모드 화면 모서리 3회 탭.

| 화면 | 구성 |
|---|---|
| 홈 | 실시간 / 녹화 / 재생 / 세션 목록 |
| 녹화 | 카메라 미리보기, 추적 상태, 깊이 지원 여부, 저장 FPS, 경과 시간, 장면 ID 선택(부록 A의 S01~S10), 파지 오프셋 입력, 시작·정지 |
| 디버그 오버레이 | 카메라 + 깊이 컬러맵 반투명 오버레이 + 선택 대표점 표시 / 위에서 본 미니맵(복셀, 통로, 진행 방향, 물체 AABB, 대표점 3방식 구분 표시, 높이 분류 색) / 텍스트: 상태, 방위각, 거리, 구간, 정보 나이, 느린 경로 Hz, 발열 상태 / **현재 재생 중인 음원을 크게 표시** (시연용) |
| 실험 패널 | 대표점 방식, 통로 폭, 거리 구간, 동시 음원 수, 복셀 크기를 실행 중 변경. 변경 이력은 로그에 기록 |
| 세션 목록 | 세션별 크기·길이·장면 ID, `adb pull` 경로 표시, 삭제 |

### 11.4 시연

- scrcpy로 S10 화면(디버그 오버레이)을 노트북·프로젝터에 미러링한다.
- 청중은 음향을 들을 수 없으므로 오버레이에 현재 음원의 방향·구간을 크게 표시한다.
- 대비책: 재생 모드 화면 녹화 + 바이노럴 출력 녹음을 합친 시연 영상(헤드폰 청취용)을 미리 준비한다.

---

## 12. 설정 (`assets/config/default.json`, 실험 패널에서 덮어쓰기)

모든 값은 **가설**이며 §10.2 지표로 조정한다.

| 키 | 초기값 | 비고 |
|---|---|---|
| `head.offsetFromCameraM` | [0, 0, 0] | 기준 파지에서 **실측 후 설정** |
| `heading.windowS` / `minTravelM` | 1.0 / 0.15 | |
| `corridor.widthM` / `heightM` / `lengthM` / `behindM` | 0.8 / 2.0 / 3.5 / 0.2 | |
| `depth.subsample` | 2 | 역투영 픽셀 간격 |
| `depth.source` / `minConfidence` | SMOOTHED / 0 | 느린 경로 입력 깊이(F6, M3 비교로 결정), 원시 깊이 신뢰도 하한(0~255) (M3 추가) |
| `map.voxelSizeM` | 0.05 | 비교 실험 대상 |
| `map.hitGain` | 0.2 | 관측 1회당 score 증가 (M3 추가) |
| `map.minHits` / `minScore` | 3 / 0.1 | |
| `map.freeMarginM` | 0.15 | 빈 공간 감쇠 여유 |
| `map.decayPerObservation` | 0.3 | 시야 안 빈 공간 관측 1회당 감쇠 |
| `map.passedMarginM` / `maxUnseenS` / `radiusM` | 1.0 / 10 / 5.0 | 시야 밖 복셀 삭제 조건 |
| `floor.searchBandM` / `toleranceM` | 0.5 / 0.05 | 첫 추정은 카메라보다 낮은 점 전체, 이후 직전 바닥 ± searchBandM (M3 해석) |
| `floor.binM` / `emaAlpha` / `minPoints` / `belowMarginM` | 0.02 / 0.2 / 200 / 0.10 | 히스토그램 칸, 평활, 최소 점 수, 단차 후보 기준 (M3 추가) |
| `cluster.epsM` / `minSamples` | 0.15 / 5 | |
| `cluster.headMinM` / `bodyMinM` | 1.2 / 0.5 | 통로 내 부분 기준 |
| `track.matchRadiusM` / `emaAlpha` | 0.3 / 0.3 | |
| `repPoint.strategy` | CORRIDOR_NEAREST | |
| `policy.stopM` / `warnMaxM` / `silentMaxM` | 1.0 / 2.5 / 3.0 | |
| `policy.hysteresisM` / `maxSources` / `maxInfoAgeMs` | 0.15 / 1 / 300 | |
| `state.recoverFrames` / `recoverScoreScale` / `unknownRepeatS` | 10 / 0.5 / 3.0 | |
| `state.maxSpeedMps` / `maxAngularSpeedDps` | 3.0 / 600 | 자세 불연속 판정 (v0.2.1). M1 실측: 정상 보행 p99 ≤ 0.7 m/s(실내)·2.6 m/s(야외), 정상 회전 최대 214°/s, 점프 ≥ 5.7 m/s |
| `audio.sampleRate` / `blockSize` / `masterGainDb` | 48000 / 256 / −12 | |
| `record.depthEveryN` / `rgbEveryN` | 1 / 3 | 저장 간격 (F7 결과로 조정) |
| `record.deviceLogIntervalS` | 1.0 | `device.csv` 기록 주기 (M1 추가) |
| `align.fitLengthM` | 2.0 | 분석 도구용 |

---

## 13. 마일스톤

각 마일스톤은 **완료 기준 충족 + 사용자 확인**으로 끝난다. 변경 사항은 `docs/DECISIONS.md`에 기록한다.

### 13.1 필수

| # | 내용 | 완료 기준 |
|---|---|---|
| M0 | 저장소 골격: `core`(JVM) + `app`(Android) + `tools/analysis`, 버전 카탈로그, 설정 로더, `Types.kt` | `./gradlew :core:test` 통과, 빈 앱이 S10에 설치·실행 |
| M1 | ARCore 세션, 깊이 지원 확인, **녹화 모드**(§8.1), 개발 모드 홈·녹화 화면, 세션 목록 | S01·S02 녹화 성공, 스파이크 F1~F7 결과를 `docs/FORMAT.md`에 기록 |
| M2 | `core` 기하 + 합성 장면 생성기 + `SessionReader` | 합성 장면에서 역투영 점이 도형 표면 위(오차 < 1 cm). 규약 변환 왕복 테스트 |
| **G1** | **형식 v1 확정** (§8.4) | F1~F7 결과 반영, `SessionReader`로 실제 세션 읽기 성공 |
| M3 | 바닥 + 복셀 맵(시야 밖 보존 포함) | SC-01~SC-04, **SC-07(시야 밖으로 나가는 낮은 상자 보존)** 통과 |
| M4 | 군집 + 높이 분류(통로 내 부분) + 추적 + 대표점 3방식 | SC-05·**SC-06(벽에 붙은 머리 높이 돌출물 → `HEAD`)** 통과, id 유지 |
| M5 | 진행 방향 + 통로 + 거리 구간 + 상태 기계 | SC-08(손목 요 흔들림) 진행 방향 안정, 히스테리시스, SC-10(추적 상실) → `UNKNOWN`, SC-12(추적 중 자세 점프) → `UNKNOWN`·맵 초기화, SC-13(깊이 정지) → 정보 만료로 `UNKNOWN` |
| M6 | HRTF 추출 스크립트 + `Hrtf`/`Sounds`/`BinauralRenderer` | PC에서 방위각 −60°~+60° 스윕 WAV 생성, 블록 경계 클릭 없음, 좌우 에너지비가 방위각에 단조. 사용자가 헤드폰으로 방향 확인 |
| M7 | `app` 통합: 스레드 배치, 실시간·재생 모드, `AudioTrack` 출력, 디버그 오버레이, 실행 로그 | S02 재생 모드에서 의자 방향으로 소리가 나고, 로그가 §10.1 형식으로 생성. 스파이크 F8(같은 세션 재생 2회 비교) 결과를 `docs/FORMAT.md`에 기록 |
| M8 | 오프라인 재생 테스트(PC) + Python 분석 도구(정렬·지표·보고서) | 합성 세션(잡음 0)에서 방향 오차·과대추정 ≈ 0, 잡음 주입 시 지표가 기대 방향으로 변화. 실제 세션 1개로 전 지표 산출 |
| M9 | 사용자 모드 UI, 알림음·진동, 오디오 포커스 | TalkBack 켠 상태에서 시작·일시정지·종료 가능 |
| M10 | 부록 A 장면 전체 녹화, 대표점 3방식·복셀 크기 비교, 10분 지속 동작 | 비교표(`report.py`)와 파라미터 조정안 |

### 13.2 선택 (필수 완료 후, 측정 근거가 있을 때)

| # | 내용 | 착수 조건 |
|---|---|---|
| O1 | 에뮬레이터 재생 환경 정리 | F9 가능 확인 시 (기기 병목 완화) |
| O2 | 깊이 신경망 보조(LiteRT, 스케일 정합) | 돌출부·얇은 구조 탐지율이 부족할 때. 모델·라이선스는 사용자 승인 |
| O3 | 빠른 경로의 IMU 직접 외삽 | 자세 갱신만으로 움직임 → 소리 지연이 부족할 때 |
| O4 | 동시 음원 2~3개 | 1개 기준 지표 확보 후 |
| O5 | C++ 저지연 오디오 출력 | `AudioTrack` 출력 지연이 부족할 때 |
| O6 | 실험 패널 설정 프리셋 저장·불러오기 | 비교 실험이 잦아질 때 |

---

## 14. Claude Code 작업 규칙

1. 한 번에 한 마일스톤만 진행한다. 시작 전에 만들 파일·클래스 목록과 테스트 계획을 짧게 제시한다.
2. `core`에는 Android·ARCore import를 넣지 않는다. 위반은 빌드에서 걸리도록 모듈 의존성을 설정한다.
3. 모든 공개 함수에 KDoc 한 줄. 단위는 이름에 포함(`M`, `Ns`, `Deg`, `Mm`).
4. ARCore·Android API는 공식 문서와 공식 샘플로 확인 후 사용한다. 확인하지 못한 사양은 코드에 `// VERIFY:` 주석으로 표시하고 사용자에게 알린다.
5. 새 의존성, 모델 가중치, 데이터셋, HRTF 파일은 **사용자 승인 후** 추가하고 `docs/LICENSES.md`에 코드·가중치·데이터 라이선스를 구분해 기록한다.
6. 테스트는 합성 장면으로 작성한다. 실제 녹화 데이터(`data/`)와 서명 키는 git에 올리지 않는다. 예외로, 기기 없이 PC에서 같은 입력으로 `core`를 돌리기 위한 대표 세션 몇 개의 경량본(`arcore.mp4` 제외, 형식 그대로)은 `testdata/sessions/`에 둔다(v0.2.2). 자동 테스트의 합격 기준은 계속 합성 장면이다.
7. 성능 최적화는 로그로 병목을 확인한 뒤에만 한다.
8. 셸 명령은 PowerShell 기준으로 안내한다.
9. 안전 원칙(§2.2)에 어긋나는 단순화(예: 확인 불가 상태에서 이전 음원 유지)는 하지 않는다.
10. `core` 알고리즘은 `prototypes/<언어>/<모듈>/`에서 다른 언어로 먼저 만들 수 있다. 최종 구현과 마일스톤 완료 판정은 Kotlin `core`이며, 이식 규칙(데이터 계약·float32·설정 원본·골든 벡터)은 README §4.8을 따른다.

---

## 15. 미결정 사항

| 항목 | 현재 방침 | 결정 시점 |
|---|---|---|
| ARCore 규약·이미지 방향·깊이 사양 | 스파이크 F2~F6 | M1 |
| 에뮬레이터 재생 | 스파이크 F9 | M1~M2 |
| HRTF 데이터셋 | 공개 SOFA 중 라이선스 확인 후 선택 | M6 시작 전 |
| 손–머리 오프셋 | 기준 파지에서 실측 | M1 녹화 시 |
| 이어폰 기준 | 유선 기준, 블루투스는 지연 측정 후 판단 | M7 |
| 모듈 담당 | 녹화·UI·앱 통합 / 맵·추적 / 음향 분담안을 팀에서 확정 (특히 3D·대표점) | M0 전 |
| 알림음·진동 패턴 | 가설로 구현 후 사용자 평가 | M9 이후 |

---

## 부록 A. 촬영 프로토콜

**공통 조건**
- 실내 복도. 바닥에 시작 표시와 **보행선 테이프**(정답 정렬에 사용), 보행선을 따라 직진.
- 기준 파지: 가슴 앞 중앙, 높이 약 1 m, 카메라 진행 방향. 파지 오프셋을 1회 실측해 녹화 화면에 입력.
- 초속 약 1 m (메트로놈 앱으로 걸음 박자 고정 권장). 시작 전 2~3초 정지(추적 안정화).
- 장애물 위치는 시작 표시 기준 줄자로 재 `annotations/obstacles.json`에 기록.
- 장애물은 부딪혀도 안전한 것(종이 상자, 스티로폼 판 등) 우선. 촬영자 옆에 보조자 동행.
- 장면당 3회 반복.

| 장면 | 구성 | 확인 목적 |
|---|---|---|
| S01 | 장애물 없는 복도 | 오경보, 바닥 분리, 벽 처리 |
| S02 | 보행선 위 2 m 지점 의자 | 기본 경고 시점·방향 |
| S03 | 테이블 모서리가 통로로 돌출 | 대표점 3방식 비교 |
| S04 | 얇은 기둥(지름 5 cm 내외) | 얇은 구조 탐지 |
| S05 | **벽에 붙은** 머리 높이 돌출물(약 1.6 m, 벽에서 30 cm 돌출) | 머리 높이 분류(통로 내 부분 판정) |
| S06 | 통로 옆 40~60 cm의 장애물 | 통로 밖 제외 |
| S07 | 4 m 이상 먼 물체 + 1.5 m 지점 낮은 상자 | 제외 거리, **낮은 상자의 시야 밖 보존과 `STOP`** |
| S08 | S02를 저조도에서 | 견고성 |
| S09 | 무늬 없는 벽·유리문 앞 | 이상치, `UNKNOWN` 전환 |
| S10 | S02 + 손목을 좌우로 크게 회전 | 진행 방향 추정, 음원 고정 |
| T01 | 10분 연속 실행 | 발열, 저장 처리량, 지속 동작 |

## 부록 B. 합성 장면 목록 (`core` 테스트)

| ID | 장면 | 검증 대상 |
|---|---|---|
| SC-01 | 바닥만 | 바닥 추정, 오경보 없음 |
| SC-02 | 보행선 위 2 m 상자 | 역투영·맵·경고 시점 |
| SC-03 | 통로 양옆 벽 | 벽이 통로 밖으로 분류 |
| SC-04 | 상자가 중간에 제거됨(이동 물체 흔적) | 시야 안 빈 공간 감쇠 |
| SC-05 | 테이블 모서리 돌출 | 대표점 3방식 차이 |
| SC-06 | **벽에 붙은 머리 높이 판** | 통로 내 부분 기준 `HEAD` 분류 |
| SC-07 | **1.5 m 지점 낮은 상자 → 1 m 이내로 접근하며 시야 밖으로 나감** | 시야 밖 보존, `STOP` 발생 |
| SC-08 | SC-02 + 손목 요 흔들림 ±20° | 진행 방향·방위각 안정 |
| SC-09 | SC-02 + 손–머리 오프셋 0.2 m (보정 없음 vs 보정) | 오프셋에 의한 방위각 편향과 보정 효과 |
| SC-10 | SC-02 + 1초 추적 상실 | `UNKNOWN` 전환·복귀 |
| SC-11 | SC-02 + 깊이 스케일 편향 +10% | 과대추정 지표·경고 지연이 기대대로 나타나는지 |
| SC-12 | SC-02 + `TRACKING` 유지 중 월드 좌표 점프(수 m·수십 °) | 자세 불연속 감지 → `UNKNOWN`, 맵 초기화 후 복귀 (v0.2.1) |
| SC-13 | SC-02 + 깊이 정지(같은 타임스탬프 깊이 반복) | 정보 나이 증가 → 음원 중단·`UNKNOWN` (v0.2.1) |

## 부록 C. 분석 도구 (`tools/analysis`)

| 스크립트 | 입력 | 출력 |
|---|---|---|
| `extract_hrir.py` | 승인된 SOFA 파일 | 앱용 수평면 HRIR 바이너리 |
| `align.py` | 세션 `frames.csv` + `obstacles.json` | 정렬 변환, 잔차 |
| `metrics.py` | 실행 로그 + 정렬 결과 + 정답 | `metrics.json` (§10.2) |
| `report.py` | 여러 `metrics.json` | 비교표(Markdown) |
| `plots.py` | 실행 로그 | 방위각·거리·구간 시계열, 지연 분포, 위에서 본 궤적과 장애물 |
