# 시각장애인 보행 보조 앱 (캡스톤디자인 3조)

> 「시각 정보의 청각 변환을 활용한 시각장애인 보행 보조 서비스」 캡스톤디자인(1) MVP

## 목차

1. [프로젝트 소개](#1-프로젝트-소개)
2. [팀 구성](#2-팀-구성)
3. [실행해 보기 (재현)](#3-실행해-보기-재현)
4. [개발 안내](#4-개발-안내)
5. [저장소 관리 규칙 (Git)](#5-저장소-관리-규칙-git)
6. [마일스톤](#6-마일스톤)
7. [산출물](#7-산출물)

---

## 1. 프로젝트 소개

Galaxy S10 5G(SM-G977N)에서 동작하는 Android 앱이다. ARCore의 자세와 깊이로 **월드 좌표에 고정된 로컬 3D 맵**을 만들고, 진행 통로 안의 가장 가까운 장애물을 **HRTF 공간음향**으로 이어폰에 알려 준다.

| 모듈 | 언어 | 역할 |
|---|---|---|
| `core/` | 순수 Kotlin(JVM) | 알고리즘 전부(기하·맵·추적·안내 정책·음향 합성). Android 의존성 없음, PC에서 합성 장면으로 테스트 |
| `app/` | Kotlin(Android) | ARCore 세션, 녹화·실시간·재생 모드, 오디오 출력, UI |
| `tools/analysis/` | Python 3.11 | 녹화·실행 로그 분석 전용 |
| `docs/` | Markdown | 명세와 기록 (아래 표) |

| 문서 | 내용 |
|---|---|
| [`docs/MVP_SPEC.md`](docs/MVP_SPEC.md) | **구현의 단일 기준.** 범위, 설계 원칙, 모듈 명세, 마일스톤 |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | 결정 기록 (한 줄씩) |
| [`docs/FORMAT.md`](docs/FORMAT.md) | 녹화 세션 형식과 스파이크(F1~F9) 결과 |
| [`docs/LICENSES.md`](docs/LICENSES.md) | 의존성·모델·데이터 라이선스 |

### 반드시 지킬 설계 원칙 (MVP_SPEC §2.2)

1. 현재 시각 이후의 프레임·자세를 쓰지 않는다 (보간 금지, 과거 값만)
2. 스레드 간 전달은 큐가 아니라 최신 값 교체
3. 정보 나이가 허용치를 넘으면 음원을 재생하지 않는다
4. 무음을 "장애물 없음"으로 안내하거나 기록하지 않는다
5. 시야 밖 복셀은 감쇠하지 않는다
6. 수치는 하드코딩하지 않고 설정(`app/src/main/assets/config/default.json`)에서 읽는다
7. `core`에는 Android·ARCore import를 넣지 않는다

---

## 2. 팀 구성

| 이름 | GitHub | 담당 |
|---|---|---|
| (이름) | [@min64383](https://github.com/min64383) | (저장소 관리자) |
| (이름) | [@2j2h5](https://github.com/2j2h5) | |
| (이름) | 박상우 | |

담당 분담안(MVP_SPEC §15): 녹화·UI·앱 통합 / 맵·추적 / 음향

---

## 3. 실행해 보기 (재현)

처음 받은 사람이 PC를 준비하고 → 기기를 준비·연결하고 → 녹화하고 → 녹화 세션을 PC에서 분석하기까지의 순서다. 명령은 **Windows PowerShell**, 저장소 루트 기준이다.

```
3.1 PC 준비 ──────▶ 3.2 기기 준비 ──▶ 3.3 연결·앱 설치 ──▶ 3.4 녹화 ──────▶ 3.6 PC로 가져와 분석
setup-windows.ps1    (폰에서 직접)     check-device.ps1     (폰에서 직접)     pull-sessions.ps1
```

스크립트는 모두 `tools/setup/`에 있다. Windows는 기본적으로 `.ps1` 실행을 막으므로 `powershell -ExecutionPolicy Bypass -File <스크립트>` 형태로 실행한다(이 실행에만 적용되고 시스템 설정은 바꾸지 않는다).

### 3.1 PC 준비 (Windows 11)

#### 필요한 것

| 항목 | 버전 | 용도 | 스크립트 동작 |
|---|---|---|---|
| Git | 최신 | 저장소 받기 | 없으면 `winget install Git.Git` |
| Android Studio | 최신 안정판 | 내장 JDK(JBR)로 Gradle 실행, 디버깅 | 없으면 `winget install Google.AndroidStudio` |
| JDK | 17 이상 (Android Studio 내장 JBR) | AGP 9.4 빌드 | 버전만 확인 |
| Android SDK cmdline-tools | 최신 | SDK 패키지 설치 | 없으면 Google SDK 저장소에서 받아 SHA-1 확인 후 설치 |
| Android SDK platform-tools | 최신 | `adb` | 없으면 설치 |
| Android SDK Platform | `compileSdk` (현재 37) | 앱 컴파일 | `app/build.gradle.kts`에서 읽어 없으면 설치 |
| uv + Python | Python 3.11 | 분석 도구 `.venv` | 없으면 `winget install astral-sh.uv` → Python 3.11 설치 → `.venv` 생성 → `requirements.txt` 설치 |
| scrcpy (선택) | 최신 | 시연용 화면 미러링 | `-WithScrcpy`일 때만 설치 |

빌드 도구 버전(AGP 9.4.0, Kotlin 2.4.20, Gradle 래퍼 9.8.0, ARCore 1.56.0)은 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)에 고정되어 있다. Gradle은 래퍼가 내려받으므로 따로 설치하지 않고, SDK build-tools도 첫 빌드 때 AGP가 필요한 버전을 자동으로 받는다.

#### 실행

```powershell
git clone https://github.com/min64383/Capstone_Design-team3.git   # Git이 없으면 GitHub에서 ZIP으로 받아도 된다
cd Capstone_Design-team3

powershell -ExecutionPolicy Bypass -File tools\setup\setup-windows.ps1 -CheckOnly   # 1) 무엇이 없는지 확인만 (아무것도 바꾸지 않음)
powershell -ExecutionPolicy Bypass -File tools\setup\setup-windows.ps1              # 2) 없는 것 설치 + 설정
```

- 여러 번 실행해도 안전하다. 이미 있는 것은 건너뛰고, 끝에 항목별 상태 표를 보여 준다. 준비되지 않은 항목이 남으면 종료 코드 1.
- Android Studio·Git 설치 중 **관리자 권한 확인 창**이 뜨면 허용한다.
- Android Studio를 기본 위치가 아닌 곳에 설치했다면 `-StudioDir "D:\Android Studio"`, SDK 위치를 바꾸려면 `-SdkRoot <경로>`를 붙인다.
- 끝나면 **새 PowerShell 창**을 열어야 바뀐 환경 변수가 적용된다.

#### 환경 변수 관리

스크립트는 **사용자 범위**(`HKCU\Environment`)에만 쓰고, 바꾸기 전에 기존 값을 `%LOCALAPPDATA%\WalkAssist\env-backup-<시각>.json`에 백업한다.

| 변수 | 값 | 이유 |
|---|---|---|
| `JAVA_HOME` | `C:\Program Files\Android\Android Studio\jbr` | `gradlew`가 이 JDK로 실행됨 |
| `ANDROID_HOME` | `%LOCALAPPDATA%\Android\Sdk` | Gradle·도구가 SDK를 찾음 |
| `Path`에 추가 | `<SDK>\platform-tools`, `<SDK>\cmdline-tools\latest\bin` | `adb`, `sdkmanager`를 어디서나 실행 |

- `Path`는 원문(`%VAR%` 포함)을 그대로 보존하고 형식(`REG_EXPAND_SZ`)을 유지한다. 중복 항목과 빈 항목은 정리한다.
- 예전 변수 `ANDROID_SDK_ROOT`가 `ANDROID_HOME`과 다르면 경고한다(두 값이 다르면 Gradle이 오류를 낸다).
- 저장소의 `local.properties`(git 제외)의 `sdk.dir`도 같은 SDK 경로로 맞춘다.
- 되돌리기: `powershell -ExecutionPolicy Bypass -File tools\setup\setup-windows.ps1 -RemoveEnv` — `JAVA_HOME`, `ANDROID_HOME`, `Path`의 SDK 항목을 지운다(먼저 백업). 설치한 프로그램은 지우지 않는다(필요하면 `winget uninstall <ID>`).

#### 확인: core 테스트 (기기 없이)

```powershell
.\gradlew.bat :core:test
```

### 3.2 Android 기기 준비

기준 기기는 **Galaxy S10 5G (SM-G977N, Android 12)**다. 메뉴 이름은 Samsung One UI 기준이며 다른 기기는 조금 다를 수 있다. 기기마다 처음 한 번만 하면 된다.

**① 개발자 옵션 켜기**

1. `설정` → `휴대전화 정보` → `소프트웨어 정보`
2. `빌드번호`를 **7번 연속** 누른다 → 잠금 화면 PIN 입력 → "개발자 모드를 켰습니다"
3. `설정` 맨 아래에 `개발자 옵션` 메뉴가 생긴다

**② USB 디버깅 켜기**

1. `설정` → `개발자 옵션` → `USB 디버깅` 켬 → 확인
2. (권장) 같은 화면의 `화면 켜짐 상태 유지`(충전 중에는 화면이 꺼지지 않음)를 켜면 연결 작업이 편하다

**③ ARCore 설치: Google Play 서비스(AR)**

- Play 스토어에서 **"Google Play 서비스(AR)"**(패키지 `com.google.ar.core`)를 설치하거나 업데이트한다. [Play 스토어 링크](https://play.google.com/store/apps/details?id=com.google.ar.core)
- 미리 설치하지 않아도 앱을 처음 열면 ARCore 설치 화면으로 안내되고, `check-device.ps1`도 없으면 폰에서 Play 스토어 페이지를 연다.
- S10 5G는 [ARCore 지원 기기 목록](https://developers.google.com/ar/devices)에 Depth API 지원 기기로 올라 있다(F1 확인됨, `docs/FORMAT.md`).

**④ 녹화 전 확인**

- 여유 저장 공간 **5 GB 이상**, 배터리 충분히(10분 녹화 T01은 발열·배터리 소모가 크다)
- 카메라 렌즈 청소, 케이스가 카메라를 가리지 않는지

### 3.3 기기 연결과 앱 설치

1. **데이터 전송이 되는 USB 케이블**로 PC와 연결한다(충전 전용 케이블은 `adb`에 보이지 않는다).
2. 폰에 **"USB 디버깅을 허용하시겠습니까?"** 창이 뜨면 `이 컴퓨터에서 항상 허용`을 체크하고 `허용`.
3. 연결 확인 스크립트를 실행한다.

```powershell
powershell -ExecutionPolicy Bypass -File tools\setup\check-device.ps1            # 연결 상태 확인
powershell -ExecutionPolicy Bypass -File tools\setup\check-device.ps1 -Install   # + 앱 빌드·설치, 카메라 권한 부여
powershell -ExecutionPolicy Bypass -File tools\setup\check-device.ps1 -Launch    # + 앱 실행 (개발 모드 홈)
```

`check-device.ps1`이 확인하는 것 (문제가 있으면 원인별 해결 방법을 출력하고 종료 코드 1):

| 단계 | 확인 | 문제일 때 안내 |
|---|---|---|
| adb | PATH, `ANDROID_HOME`, 기본 SDK 위치에서 `adb.exe`를 찾고 서버 시작 | `setup-windows.ps1` 실행 |
| 연결 | `adb devices -l`에 기기가 나타날 때까지 최대 20초(`-WaitSeconds`) 대기 | 기기 없음: 케이블·USB 디버깅·USB 모드 확인, Windows가 폰 자체를 못 보면 [Samsung USB 드라이버](https://developer.samsung.com/android-usb-driver) · `unauthorized`: 폰의 허용 창 · `offline`: 재연결 · 여러 대: `-Serial <시리얼>` |
| 기기 | 모델, Android 버전, SoC | 기준 기기(SM-G977N)가 아니면 경고 |
| ARCore | `com.google.ar.core` 설치 여부와 버전 | 없으면 폰에서 Play 스토어 페이지를 연다 |
| 저장 공간·배터리 | 내부 저장소 여유 공간 5 GB 이상 | 오래된 세션을 PC로 옮긴 뒤 삭제 |
| 앱 | `walkassist.app` 설치 여부·버전, 기기에 쌓인 세션 수 | `-Install` |

`-Install`은 선택된 기기(`ANDROID_SERIAL`)에 `.\gradlew.bat :app:installDebug`를 실행하고 `pm grant`로 카메라 권한을 미리 준다. 직접 할 때:

```powershell
adb devices                      # 목록에 "<시리얼>  device"로 보이면 연결된 것
.\gradlew.bat :app:installDebug
adb logcat -s WalkAssist         # 앱 로그 (녹화 중 GL 스레드 구간별 소요 시간 'gl timing' 포함)
```

### 3.4 녹화하기

앱 이름은 **WalkAssist Dev**다. 첫 실행 때 카메라 권한을 허용하고, ARCore가 없거나 오래됐으면 설치 화면을 거친다.

1. **개발 모드 홈** → `녹화`. (`실시간`·`재생`은 M7에서 활성화)
2. 화면 위쪽 상태 표시를 확인한다.
   - `추적: TRACKING` — 폰을 천천히 좌우로 움직여 주변을 비추면 `PAUSED`에서 `TRACKING`으로 바뀐다. 문제가 있으면 `(INSUFFICIENT_LIGHT)` 같은 사유가 붙는다
   - `깊이 지원(F1): AUTOMATIC=true RAW=true → AUTOMATIC`
3. 화면 아래쪽에서 설정한다.
   - **장면 ID**: `S01`~`S10`, `T01` ([MVP_SPEC 부록 A](docs/MVP_SPEC.md#부록-a-촬영-프로토콜)의 장면 표)
   - **파지 오프셋 x, y, z (m)**: 카메라에서 머리까지의 거리(월드 수평 기준). 기준 파지에서 한 번 실측해 입력한다. 기본값은 설정 `head.offsetFromCameraM`
4. `녹화 시작`을 누르면 상태 표시가 `● REC 00:12 <세션ID>`로 바뀌고 실시간 통계가 나온다.
   - `프레임 · 깊이 (fps) · 원시 · RGB`: 지금까지 저장한 개수. 깊이 fps가 30 가까이면 정상
   - `건너뜀`: 저장 스레드가 밀려서 파일(깊이·이미지)을 건너뛴 프레임 수. 자세 행은 빠지지 않는다
   - `쓰기 오류`, `발열`, `배터리`
5. 촬영 절차(부록 A 공통 조건): 시작 표시에서 **2~3초 정지** → 가슴 앞 중앙, 높이 약 1 m로 들고 **초속 약 1 m로 보행선을 따라 직진** → 끝에서 정지.
6. `녹화 정지` → "저장 완료: `<세션ID>`" 알림이 뜨면 끝. 정지하기 전에 앱을 벗어나면(홈 버튼, 화면 꺼짐) 그 시점에 녹화가 자동으로 멈추고 저장된다.
7. 개발 모드 홈 → `세션 목록`에서 세션별 길이·프레임 수를 확인하거나 삭제할 수 있다. 화면 위에 `adb pull` 명령이 표시된다.

녹화 화면은 **세로 고정**이다(녹화마다 센서 방향과 화면 방향의 관계를 같게 두기 위해).

### 3.5 녹화 세션에 담기는 정보 (형식 v0)

세션 하나는 기기의 `/storage/emulated/0/Android/data/walkassist.app/files/sessions/<세션ID>/` 폴더 하나이고, 세션 ID는 `<yyyyMMdd_HHmmss>_<장면ID>`(녹화 시작 시각)다. 형식 정의 코드는 `core/session/`(`SessionFormat.kt`, `SessionMeta.kt`, `Png16.kt`)에 있어 앱과 PC가 같은 코드를 쓴다. 상세와 스파이크 결과는 [`docs/FORMAT.md`](docs/FORMAT.md)에 있다. **v0은 스파이크용 초안**이고 G1에서 v1로 확정된다.

```
20260926_050843_S01/                 실측 예: 21초 녹화, 합계 64 MB
├── meta.json          4 KB   기기·ARCore·깊이 지원·카메라 내부 파라미터·파지 오프셋·규약·통계
├── arcore.mp4        42 MB   ARCore Recording API 출력 (재생 모드 입력)
├── frames.csv       164 KB   ARCore 프레임마다 1행 (622행)
├── device.csv         1 KB   발열·배터리, 1초 간격
├── depth/           8.9 MB   일반 깊이 PNG 619개
├── raw_depth/       3.6 MB   원시 깊이 PNG 233개
├── depth_conf/      2.0 MB   원시 깊이 신뢰도 PNG 233개
└── rgb/             7.2 MB   카메라 이미지 JPEG 208개
```

용량은 대략 **3 MB/s**(그중 MP4가 약 2/3)라서 10분 녹화(T01)는 약 2 GB로 예상된다(F7에서 확정).

#### 파일별 내용

| 파일 | 형식 | 내용 |
|---|---|---|
| `meta.json` | JSON (UTF-8) | `formatVersion`, `sessionId`, `sceneId`, `createdAt`(ISO-8601)<br>`device`: 제조사·모델·SoC·Android 버전<br>`arcore`: 빌드에 쓴 SDK 버전, 기기의 ARCore APK 버전<br>`depth`: AUTOMATIC/RAW_DEPTH_ONLY 지원 여부, 사용 모드, 깊이 해상도<br>`camera`: CPU 이미지와 GPU 텍스처의 내부 파라미터(fx, fy, cx, cy, 크기; **센서 방향, 회전 안 함**), 화면 회전, fps 범위<br>`gripOffsetM`, `elapsedMinusMonotonicNs`(시계 기준 판별용), `depthEveryN`, `rgbEveryN`<br>`conventions`: 각 값의 출처 API와 좌표·시간 규약 설명<br>`stats`: 프레임 수, 저장 수, 건너뜀, 쓰기 오류, 길이(초). **녹화가 정상 종료되어야 채워진다**(`null`이면 중단된 세션) |
| `arcore.mp4` | MP4 | ARCore Recording API(`Session.startRecording`)가 쓰는 데이터셋. ARCore 공식 문서 기준으로 카메라 영상과 IMU 등 ARCore가 세션을 다시 돌리는 데 필요한 센서 데이터가 들어 있다. 재생 모드(M7)가 이 파일을 ARCore에 넣어 실시간처럼 다시 돌린다(F8에서 확인 예정) |
| `frames.csv` | CSV, 헤더 1행 | 아래 열 표 참고. **저장이 밀려도 자세 행은 모든 프레임에 대해 기록** |
| `device.csv` | CSV | `tNs`(`elapsedRealtimeNanos`), `thermalStatus`(`PowerManager` 발열 단계 0~6), `batteryPct`, `audioOutputLatencyMs`(M7부터) |
| `depth/NNNNNN.png` | 16비트 흑백 PNG, 160×90 | `Frame.acquireDepthImage16Bits()`. 픽셀 값 = 거리 **mm (uint16)**, 0 = 무효. **새 시각의 깊이만** 저장(같은 깊이의 재투영 반복은 제외). `record.depthEveryN`개마다 1개 |
| `raw_depth/NNNNNN.png` | 16비트 흑백 PNG | `Frame.acquireRawDepthImage16Bits()`. 보정·채움 전 측정값(약 10 Hz). v0에만 있는 F6 비교용 |
| `depth_conf/NNNNNN.png` | 8비트 흑백 PNG | `Frame.acquireRawDepthConfidenceImage()`. 원시 깊이 픽셀별 신뢰도 0~255, 원시 깊이와 같은 프레임 |
| `rgb/NNNNNN.jpg` | JPEG, 640×480 | `Frame.acquireCameraImage()`(YUV_420_888 → JPEG). **센서 방향 그대로**(세로로 들면 장면이 90° 돌아간 가로 영상). `record.rgbEveryN`(기본 3) 프레임마다 |

`NNNNNN`은 `frameIndex`(녹화 시작 후 ARCore 프레임 순번, 6자리)라서 파일과 `frames.csv`의 행이 1:1로 이어진다.

#### `frames.csv` 열

| 열 | 내용 |
|---|---|
| `frameIndex` | 0부터 증가 |
| `tNs` | `Frame.getTimestamp()` (ns). 시간 기준은 ARCore가 정의하지 않음 → F5 |
| `sysElapsedNs` | GL 스레드가 프레임을 받은 시각 `SystemClock.elapsedRealtimeNanos()`. `sysElapsedNs − tNs`로 처리 지연을 추정 |
| `tracking`, `trackingFailure` | `TRACKING`/`PAUSED`/`STOPPED`, 실패 사유(`NONE`, `INSUFFICIENT_LIGHT`, `EXCESSIVE_MOTION` 등) |
| `tx, ty, tz, qx, qy, qz, qw` | `Camera.getPose()` 원본: **물리 카메라** 자세, ARCore GL 규약(카메라 +X 오른쪽, +Y 위, −Z 시선; 월드 +Y 위). 위치는 m, 회전은 쿼터니언 |
| `dtx … dqw` | `Camera.getDisplayOrientedPose()` 원본: 화면 방향에 맞춰 카메라 Z축으로 돌린 자세(v0, F2 확인용) |
| `depthTNs`, `depthFile` | 이 프레임에서 얻은 일반 깊이의 시각(저장하지 않았어도 기록), 저장했으면 상대 경로 |
| `rawDepthTNs`, `rawDepthFile`, `confFile` | 원시 깊이의 시각·파일, 신뢰도 파일 |
| `rgbFile` | 저장했으면 상대 경로 |

기록 규칙:

- **행에 적힌 파일은 모두 실제로 있다.** 파일 작업은 저장 스레드에 슬롯 1개로 넘기고, 슬롯이 차 있으면 그 프레임의 파일을 건너뛰고 행에 이름을 쓰지 않는다. 쓰기 오류는 `stats.nWriteErrors`에 센다.
- 자세는 **ARCore 규약 원본**으로 저장하고, 좌표 변환은 읽는 쪽(`core`)에서 한다(MVP_SPEC §5).
- 모든 시각은 ns 정수다. 깊이 시각(`depthTNs`)은 프레임 시각(`tNs`)보다 과거일 수 있다(원시 깊이는 중앙값 31 ms 과거, F5).

### 3.6 녹화 세션을 PC로 가져와 분석하기

`data/`는 git에 올리지 않는다([§5.4](#54-올리면-안-되는-것-gitignore)). 가져온 세션은 `data/sessions/<세션ID>/`에 둔다.

기기가 없는 팀원도 같은 입력으로 `core`를 돌릴 수 있도록 대표 세션 몇 개는 경량본(`arcore.mp4` 제외)으로 [`testdata/sessions/`](testdata/README.md)에 올려 둔다. 클론하면 바로 `SessionReader`로 읽을 수 있다. 세션 추가 방법은 `testdata/README.md`에 있다.

```powershell
powershell -ExecutionPolicy Bypass -File tools\setup\pull-sessions.ps1                       # 기기의 세션 목록
powershell -ExecutionPolicy Bypass -File tools\setup\pull-sessions.ps1 -Latest 1 -Analyze    # 가장 최근 1개를 가져와 분석
powershell -ExecutionPolicy Bypass -File tools\setup\pull-sessions.ps1 -Id 20260926_050843_S01 -Analyze
powershell -ExecutionPolicy Bypass -File tools\setup\pull-sessions.ps1 -All                  # 아직 안 가져온 것 전부
```

`pull-sessions.ps1`의 동작:

1. 기기의 세션마다 **크기·파일 수·정상 종료 여부**(`meta.json`에 `stats`가 있는지)·**PC에 이미 있는지**를 표로 보여 준다.
2. 고른 세션을 `adb pull`로 `data/sessions/`에 받는다. 이미 있는 세션은 건너뛴다(`-Force`면 다시 받기). 정상 종료되지 않은 세션은 경고하고 받는다.
3. 기기와 PC의 **파일 수를 비교해 검증**한다.
4. `-Analyze`면 `spike_check.py`를 실행해 결과를 화면과 `data/sessions/<세션ID>/spike_check.txt`에 남긴다.
5. 기기의 세션은 지우지 않는다. 공간이 필요하면 PC에 받은 것을 확인한 뒤 앱의 `세션 목록`에서 삭제한다.

직접 할 때:

```powershell
adb pull /storage/emulated/0/Android/data/walkassist.app/files/sessions/<세션ID> data/sessions/
.venv\Scripts\python.exe tools/analysis/spike_check.py data/sessions/<세션ID>
.venv\Scripts\python.exe tools/analysis/spike_check.py data/sessions/<세션ID> --roi 0.1   # 중앙 10% 영역 깊이 (F4 벽 거리 촬영용)
```

#### `spike_check.py`가 보여 주는 것 (M1 스파이크 F1~F7)

판정은 하지 않고 측정값만 출력한다. 사람이 결과를 보고 `docs/FORMAT.md`의 스파이크 결과 표에 옮긴다.

| 절 | 출력 |
|---|---|
| 머리말 | 세션 ID·장면·형식 버전, 기기·Android·SoC, ARCore SDK/APK 버전 |
| F1 깊이 지원 | AUTOMATIC / RAW_DEPTH_ONLY 지원 여부, 사용 모드 |
| F2 자세 규약 | 추적 프레임 비율과 실패 사유 분포, 두 자세(`getPose`, `displayOriented`)의 카메라 축이 월드에서 향하는 방향, 두 자세의 회전 차이, 이동 거리·높이 범위 |
| F3 이미지·내부 파라미터 | CPU 이미지·GPU 텍스처 K, 화면 회전, fps, 저장된 RGB 크기와 K의 일치 여부 |
| F4 깊이 해상도 | 깊이 크기·비율, 깊이 K 환산 후보 2가지(CPU K 기준 / 텍스처 K 기준), 중앙 영역 깊이 중앙값(`--roi`) |
| F5 타임스탬프 | `elapsedRealtime − monotonic`(시계 기준 판별), 프레임 간격 분포(Hz), `sysElapsedNs − tNs`, 일반·원시 깊이의 갱신 간격과 프레임 대비 지연 |
| F6 일반 vs 원시 | 유효 픽셀 비율, 둘 다 유효한 픽셀의 차이 분포, 신뢰도 분포 |
| F7 저장 처리량·발열 | 길이·fps, 종류별 저장 수와 초당 저장 수, 건너뜀·쓰기 오류, 폴더·MP4 크기, 발열 단계 변화, 배터리 변화 |

정렬·지표·보고서(`align.py`, `metrics.py`, `report.py`, `plots.py`)는 M8에서 추가된다([`tools/analysis/README.md`](tools/analysis/README.md)).

---

## 4. 개발 안내

### 4.1 작업 전에 읽을 것

1. [`docs/MVP_SPEC.md`](docs/MVP_SPEC.md)의 해당 마일스톤(§13)과 관련 절
2. [`docs/DECISIONS.md`](docs/DECISIONS.md) — 이미 내린 결정을 다시 뒤집지 않도록
3. 에이전트 지침과 모듈별 규칙: Claude는 [`CLAUDE.md`](CLAUDE.md)·[`.claude/rules/`](.claude/rules/), Codex는 [`AGENTS.md`](AGENTS.md) (내용 동일, §4.7)

명세와 코드가 충돌하면 **명세를 따른다.** 명세가 틀렸다고 판단되면 구현을 멈추고 팀에 수정을 제안한 뒤, 합의되면 명세를 먼저 고친다.

### 4.2 저장소 구조

```
.
├── core/src/main/kotlin/walkassist/core/   types, geometry, mapping, tracking, guidance, audio, pipeline, session
├── core/src/test/kotlin/walkassist/core/   합성 장면 테스트 (synth/)
├── app/src/main/java/walkassist/app/       ar, runtime, ui/dev, ui/user, render
├── app/src/main/assets/config/default.json 설정의 유일한 원본
├── tools/analysis/                         Python 분석 스크립트
├── tools/setup/                            PC 준비·기기 확인·세션 가져오기 PowerShell 스크립트 (§3)
├── prototypes/<언어>/<모듈>/                (필요할 때 생성) 다른 언어 프로토타입, Kotlin core로 이식 전제 (§4.8)
├── docs/                                   명세·결정·형식·라이선스
├── testdata/sessions/                      PC 분석용 대표 녹화 경량본 (arcore.mp4 제외, testdata/README.md)
└── data/                                   (git 제외) 녹화 세션
```

### 4.3 코드 작성 규칙

| 규칙 | 내용 |
|---|---|
| core 순수성 | `core`에 Android·ARCore import 금지, 외부 라이브러리 금지(승인 시 예외). `CorePurityTest`가 검사 |
| 안내 로직 위치 | 안내 로직은 `core`에. `app`은 변환·스레드·UI만 |
| 테스트 | `core` 테스트는 합성 장면으로 작성 (MVP_SPEC 부록 B, SC-01~SC-13) |
| 단위 표기 | 이름에 단위 포함: `M`, `Ns`, `Deg`, `Mm` |
| 설정 | 임계값은 `default.json`에 두고 MVP_SPEC §12 표도 함께 갱신 |
| API 확인 | ARCore·Android API는 공식 문서·샘플로 확인. 확인하지 못한 사양은 `// VERIFY:` 주석 |
| 성능 | 로그로 병목을 확인한 뒤에만 최적화 |
| KDoc | 공개 함수에 한 줄 |

### 4.4 기록해야 하는 것

| 상황 | 기록 위치 |
|---|---|
| 설계·버전·방식을 정함 | `docs/DECISIONS.md`에 한 줄: `날짜 · 마일스톤 · 결정 — 이유` |
| 새 의존성·모델·데이터셋·HRTF 파일 | **팀 승인 후** 추가하고 `docs/LICENSES.md`에 코드·가중치·데이터 구분해 기록 |
| 세션 형식·스파이크 결과 | `docs/FORMAT.md` |
| 명세 수정 | `docs/MVP_SPEC.md` 직접 수정 + `DECISIONS.md`에 이유 |
| 다른 언어 프로토타입을 Kotlin으로 이식 | 골든 벡터를 `core/src/test/resources/golden/`에, 이식하며 달라진 점을 `DECISIONS.md`에 (§4.8) |
| 에이전트 지침 수정 | `CLAUDE.md`·`.claude/rules/`와 `AGENTS.md`를 같은 커밋에서 함께 수정 + `check-agent-docs.ps1` 통과 (§4.7) |

### 4.5 Claude Code 사용 시

- 저장소 루트의 [`CLAUDE.md`](CLAUDE.md)가 항상 적용된다.
- `.claude/rules/`의 모듈 규칙은 **편집하는 파일의 경로**에 따라 자동으로 불러온다: `core/**` → `core.md`, `app/**` → `app.md` (각 파일 frontmatter의 `paths:`).
- 커밋 메시지 끝에 `Co-Authored-By: Claude ...` 줄이 붙을 수 있다. §5.2 형식은 그대로 지킨다.

### 4.6 Codex 사용 시

- Codex는 `CLAUDE.md`와 `.claude/`를 읽지 않는다. 대신 저장소 루트의 [`AGENTS.md`](AGENTS.md)를 자동으로 읽는다.
- Codex는 Git 루트부터 **현재 작업 디렉터리까지** 경로에 있는 `AGENTS.md`만 이어 붙여 읽는다(가까운 파일이 뒤에 붙어 우선). Claude처럼 편집하는 파일의 경로에 따라 규칙을 불러오지 않는다. 그래서 `core/AGENTS.md`처럼 나누지 않고, 모듈 규칙을 루트 `AGENTS.md`에 절("core 모듈 규칙", "app 모듈 규칙")로 넣었다. 기본 크기 한도(`project_doc_max_bytes`, 32 KiB)보다 충분히 작다. ([Codex AGENTS.md 문서](https://learn.chatgpt.com/docs/agent-configuration/agents-md))
- **저장소 루트에서** 실행한다. 하위 폴더에서 실행해도 루트 `AGENTS.md`는 읽히지만, 명령(`./gradlew ...`)이 루트 기준이다.
- 샌드박스가 네트워크를 막으면 첫 Gradle 실행(의존성 다운로드)이 실패한다. 먼저 사람이 `.\gradlew.bat :core:test`를 한 번 돌려 캐시를 채우거나 네트워크를 허용한다.
- 개인 설정(모델, 승인 모드 등)은 저장소에 넣지 않고 각자 `~/.codex/config.toml`에 둔다.

### 4.7 에이전트 공통 규칙 (Claude Code · Codex)

두 에이전트는 **같은 지침**을 서로 다른 파일로 읽는다. 구현 기준은 둘 다 `docs/MVP_SPEC.md`다.

| 지침 | Claude Code | Codex |
|---|---|---|
| 공통 (구조·명령·원칙·작업 방식) | `CLAUDE.md` (항상) | `AGENTS.md` 본문 |
| core 전용 | `.claude/rules/core.md` (`core/**`를 다룰 때) | `AGENTS.md` "core 모듈 규칙" 절 |
| app 전용 | `.claude/rules/app.md` (`app/**`를 다룰 때) | `AGENTS.md` "app 모듈 규칙" 절 |
| 새 모듈 전용 (예: `tools/`) | `.claude/rules/<모듈>.md` (frontmatter `paths:`) | `AGENTS.md`에 "<모듈> 규칙 (`<경로>/**`를 다룰 때)" 절 추가 |

**사용 방법**

- 첫 요청에 마일스톤을 밝히고 계획부터 받는다. 예: "M2를 시작한다. MVP_SPEC §7.1, §7.8, §13을 읽고 파일·클래스 목록과 테스트 계획부터 제시해." (한 번에 한 마일스톤, 계획 먼저)
- 기기 작업(`installDebug`, `adb`)은 폰이 연결된 PC에서만 된다. 연결 확인은 `tools\setup\check-device.ps1`(§3.3).
- 기록은 사람과 같다(§4.4). 에이전트가 만든 커밋·PR도 §5 규칙을 따른다.

**지침을 바꿀 때 (중요)**

위 표의 두 열은 **내용이 같아야 한다.** 규칙을 추가·수정하면 같은 커밋에서 양쪽을 함께 고치고, 확인 스크립트가 통과하는지 본다. 한쪽만 바뀌면 두 에이전트가 서로 다른 규칙으로 코드를 쓰게 된다.

```powershell
powershell -ExecutionPolicy Bypass -File tools\setup\check-agent-docs.ps1   # 같으면 "동기화됨", 다르면 한쪽에만 있는 줄 출력 + 종료 코드 1
```

스크립트는 목록 항목과 본문 문장을 줄 단위로 비교한다(제목, HTML 주석, `.claude/rules`의 frontmatter는 무시). 그래서 절 제목은 달라도 되지만 **규칙 문장은 글자까지 같게** 쓴다.

### 4.8 다른 언어로 개발할 때 (Kotlin `core`로 이식 전제)

`core` 알고리즘을 Python 등 다른 언어로 먼저 만들어 볼 수 있다. 단, **최종 구현은 항상 Kotlin `core`**다. 앱은 `core`만 쓰고, 마일스톤 완료 기준(§6)도 Kotlin `core` 테스트로 판정한다. 다른 언어 코드는 이식할 때 옮기기 쉽도록 아래 규칙을 지킨다.

#### 범위

| 할 수 있음 | 하지 않음 |
|---|---|
| `core`의 알고리즘(기하, 바닥·복셀 맵, 군집·추적·대표점, 진행 방향·통로·상태 기계, HRTF 합성)을 먼저 만들어 보기 | `app`(ARCore, 스레드, 오디오 출력, UI)을 다른 언어로 만들기 |
| 녹화 세션과 합성 장면으로 결과를 확인하기 | 앱이 다른 언어 코드를 직접 호출하게 만들기 (JNI, 임베디드 인터프리터 등) |
| 파라미터를 바꿔 가며 실험하기 | `tools/analysis/`에 알고리즘 넣기 (분석 전용) |

기본 언어는 **Python 3.11**이다(§3.1의 `.venv`를 그대로 쓴다). 다른 언어도 규칙은 같다.

#### 위치

```
prototypes/<언어>/<모듈>/          모듈 이름은 core 패키지와 같게: geometry, mapping, tracking, guidance, audio, pipeline
├── README.md                      상태(실험 중 / 이식 중 / 이식 완료 <커밋>), 대응하는 명세 절, core 파일
├── *.py                           구현
└── golden.py                      골든 벡터 생성 스크립트 (아래)
```

실행 결과·그림은 `data/` 아래에 둔다(git 제외). `prototypes/`는 처음 쓸 때 만든다.

#### 이식을 쉽게 하는 규칙

| 항목 | 규칙 | 이유 |
|---|---|---|
| 함수 경계 | 명세 §7의 클래스·함수 단위와 이름을 그대로 따른다. 입력·출력은 §6 데이터 계약(`PoseFrame`, `DepthFrame`, `Obstacle`, `ObstacleSnapshot`, `AudioCmd`, `GuidanceOutput`)과 같은 필드·이름으로 만든다(Python이면 `@dataclass(frozen=True)`) | 이식할 때 구조를 다시 설계하지 않도록 |
| 좌표·단위 | 월드(W, +Y 위)와 카메라(C_cv: +Y 아래, +Z 앞)만 쓴다(§5). 이름에 단위 포함(`distance_m`, `t_capture_ns`, `azimuth_deg`) | Kotlin과 같은 규약 |
| 수치 타입 | 실수는 **32비트**(`numpy.float32`), 시각은 **64비트 정수 ns**(`int64`). 깊이는 `uint16` mm | `core`는 `Float`/`Long`/`ShortArray`를 쓴다. 64비트로 개발하면 이식 후 결과가 달라진다 |
| 설정 | 수치는 `app/src/main/assets/config/default.json`을 읽어 쓴다. 새 파라미터는 `prototypes/<언어>/config.override.json`에 두고 기본 설정 위에 덮어쓴다 | 설정의 원본은 하나. `default.json`에 키를 먼저 넣으면 Kotlin 설정 로더가 "모르는 키" 오류를 낸다. 새 키는 이식할 때 `default.json`·`Config.kt`·명세 §12에 함께 올린다 |
| 설계 원칙 | §2.2를 그대로 지킨다: 과거 값만 쓰기, 정보 나이 만료, 시야 밖 복셀 보존, 무음 ≠ 안전 | 원칙 위반은 이식해도 위반 |
| 라이브러리 | `numpy`까지만 기본으로 쓴다. `scipy`·`sklearn`·`opencv`처럼 알고리즘 자체를 라이브러리에 맡기는 것은 쓰지 않는다. 꼭 필요하면 Kotlin으로 직접 구현할 수 있는지 먼저 확인하고, 새 의존성은 승인 후 `docs/LICENSES.md`에 기록 | `core`는 외부 라이브러리를 쓰지 않는다. 라이브러리에 기댄 알고리즘은 이식 비용이 크다 |
| 벡터화 | `numpy` 벡터 연산은 써도 되지만, 이식할 때 옮길 반복문 구조를 주석으로 적는다 | Kotlin에서는 원시 배열 반복문으로 옮긴다 |
| 반올림·나눗셈 | `np.round`(짝수 쪽으로 반올림)·음수 정수 나눗셈(`//`)은 Kotlin(`roundToInt`는 0.5를 올림, `/`는 0 쪽으로 버림)과 다르다. 복셀 인덱스처럼 경계가 중요한 곳은 `floor`를 명시한다 | 경계값에서 결과가 달라지는 흔한 원인 |
| 난수 | 난수는 시드를 고정하고, 결과가 난수 순서에 의존하지 않게 만든다 | 언어마다 난수 생성기가 다르다 |
| 입력 데이터 | 세션은 `docs/FORMAT.md` 형식 그대로 읽는다. 자세는 ARCore 원본이므로 C_cv 변환(§5)을 읽는 쪽에서 한다 | Kotlin `SessionReader`와 같은 처리 |

#### 골든 벡터: 다른 언어와 Kotlin을 잇는 테스트

프로토타입이 맞다고 확인되면, **입력과 기대 출력을 파일로 남겨** Kotlin 이식이 같은 결과를 내는지 테스트로 확인한다.

```
core/src/test/resources/golden/<모듈>/<케이스>.json
{
  "case": "voxel_map_sc02_box",            // 이름. 가능하면 부록 B 합성 장면 ID(SC-xx)를 포함
  "source": "prototypes/python/mapping @ <커밋>",
  "configOverride": { ... },               // default.json 위에 덮어쓴 값 (없으면 {})
  "input": { ... },                        // §6 데이터 계약 필드 이름 그대로
  "expected": { ... },
  "tolerance": { "abs": 1e-4 }             // float32 기준 허용 오차
}
```

- 위 예시의 `//` 주석은 설명용이다. 실제 파일은 주석 없는 JSON이어야 `core`의 `MiniJson`이 읽는다.
- 큰 배열(깊이 이미지 등)은 JSON에 넣지 않고 같은 폴더에 **16비트 PNG**(세션 형식과 같음, `core/session/Png16`로 읽음) 또는 **리틀 엔디언 float32 `.f32`** 파일로 두고 JSON에서 상대 경로로 가리킨다.
- 입력은 가능하면 부록 B 합성 장면에서 만든다. 실제 녹화 데이터(`data/`, `testdata/`)는 골든 벡터에 넣지 않는다(§5.4).
- 골든 파일은 생성 스크립트(`golden.py`)로만 만들고 손으로 고치지 않는다.

#### 이식 절차 (Kotlin으로 통합할 때)

1. 프로토타입 `README.md`의 상태를 "이식 중"으로 바꾼다.
2. 골든 벡터를 `core/src/test/resources/golden/<모듈>/`에 넣는다.
3. §4.2 구조의 해당 `core` 패키지에 Kotlin으로 구현한다. 규칙은 §4.3과 같다(외부 라이브러리 없음, 공개 함수 KDoc 한 줄, 단위를 이름에 넣기).
4. 테스트 두 종류를 통과시킨다.
   - 골든 벡터 테스트: 프로토타입과 같은 결과인지
   - 합성 장면 테스트(부록 B SC-xx): 마일스톤 완료 기준
5. 프로토타입에서 쓴 새 파라미터를 `default.json`·`Config.kt`·명세 §12 표에 올리고 `config.override.json`에서 지운다.
6. 이식하며 달라진 점(수치 타입, 반올림, 알고리즘 단순화 등)은 `docs/DECISIONS.md`에 한 줄로 남긴다.
7. 프로토타입 `README.md`를 "이식 완료 `<커밋>`"으로 바꾼다. 프로토타입 코드는 참고용으로 남겨 두고, 이후 수정은 Kotlin `core`에서만 한다.

브랜치·커밋은 §5와 같다. 프로토타입과 이식은 같은 마일스톤 접두어를 쓴다(예: 프로토타입 `m3/voxel-map-proto`, 이식 `m3/voxel-map`).

---

## 5. 저장소 관리 규칙 (Git)

### 5.1 브랜치

| 브랜치 | 용도 |
|---|---|
| `main` | 항상 빌드되고 `./gradlew :core:test`가 통과하는 상태. 직접 push하지 않고 PR로만 합친다 |
| `m<번호>/<주제>` | 마일스톤 작업. 예: `m2/geometry`, `m3/voxel-map` |
| `fix/<주제>` | 버그 수정. 예: `fix/gradle-properties-bom` |
| `docs/<주제>` | 문서만 바꾸는 작업. 예: `docs/readme` |

한 브랜치에는 한 마일스톤(또는 그 일부)만 담는다.

```powershell
git switch main
git pull
git switch -c m2/geometry
# ... 작업, 커밋 ...
git push -u origin m2/geometry
# GitHub에서 main으로 PR 생성
```

### 5.2 커밋 메시지

```
<마일스톤>: <무엇을 했는지 한 줄>

<필요하면 왜 그렇게 했는지>
```

- 마일스톤 접두어: `M0`~`M10`, `G1`, `O1`~`O6`, 해당 없으면 `docs`, `fix`, `chore`
- 예: `M1: ARCore 세션, 녹화 모드(형식 v0), 개발 모드 홈·녹화·세션 목록`
- 한 커밋에는 한 가지 변경만. 빌드가 깨지는 중간 상태를 커밋하지 않는다

### 5.3 Pull Request

- 제목은 커밋 메시지 형식과 같게
- 본문에 적을 것: 무엇을 바꿨는지, 관련 명세 절(§), 테스트 결과(`./gradlew :core:test`, 기기 확인 여부), `DECISIONS.md`에 추가한 줄
- 에이전트 지침(`CLAUDE.md`, `.claude/rules/`, `AGENTS.md`)을 바꿨다면 `tools\setup\check-agent-docs.ps1` 통과 확인 (§4.7)
- 다른 팀원 **1명 이상 확인** 후 병합. 병합 방식은 Squash 또는 Merge 중 팀에서 하나로 통일
- 병합한 브랜치는 삭제

### 5.4 올리면 안 되는 것 (`.gitignore`)

| 대상 | 이유 |
|---|---|
| `data/` (녹화 세션) | 용량이 크고 촬영 장소가 담김 (MVP_SPEC §14-6). 대표 세션 경량본만 `testdata/sessions/`에 올린다(`tools\setup\export-testdata.ps1`) |
| `*.jks`, `*.keystore`, `keystore.properties` | 서명 키 |
| `local.properties` | 개인 SDK 경로 |
| `build/`, `.gradle/`, `.kotlin/`, `.venv/`, `.idea/` | 빌드·환경 산출물 |

실수로 올렸다면 바로 팀에 알리고, 키는 폐기 후 새로 만든다(이력에서 지워도 이미 복제된 사본은 남는다).

### 5.5 태그

마일스톤이 완료 기준을 충족하고 팀 확인을 받으면 `main`에 태그를 단다.

```powershell
git tag -a m1 -m "M1 완료: 녹화 모드, 스파이크 F1~F7"
git push origin m1
```

---

## 6. 마일스톤

각 마일스톤은 **완료 기준 충족 + 팀 확인**으로 끝난다. 상세는 [MVP_SPEC §13](docs/MVP_SPEC.md#13-마일스톤).

### 6.1 필수

| # | 내용 | 완료 기준 | 상태 |
|---|---|---|---|
| M0 | 저장소 골격, 버전 카탈로그, 설정 로더, `Types.kt` | `:core:test` 통과, 빈 앱이 S10에 설치·실행 | ✅ 완료 |
| M1 | ARCore 세션, 깊이 지원 확인, 녹화 모드, 개발 모드 홈·녹화 화면, 세션 목록 | S01·S02 녹화, 스파이크 F1~F7 결과 기록 | ✅ 완료 (F4 깊이 척도는 줄자 재촬영 필요, [FORMAT.md](docs/FORMAT.md)) |
| M2 | `core` 기하 + 합성 장면 생성기 + `SessionReader` | 역투영 오차 < 1 cm, 규약 변환 왕복 테스트 | ✅ 완료 (최대 0.54 mm) |
| **G1** | **세션 형식 v1 확정** | F1~F7 반영, 실제 세션 읽기 성공 (F8은 M7) | ⏳ |
| M3 | 바닥 + 복셀 맵(시야 밖 보존) | SC-01~04, SC-07 | ✅ 완료 (실제 데이터 바닥 누출 해결, 명세 v0.2.3) |
| M4 | 군집 + 높이 분류 + 추적 + 대표점 3방식 | SC-05·SC-06, id 유지 | ✅ 완료 (실제 복도: 통로 안 복셀만 군집, 명세 v0.2.4) |
| M5 | 진행 방향 + 통로 + 거리 구간 + 상태 기계 | SC-08 안정, 히스테리시스, SC-10·SC-12·SC-13 → `UNKNOWN` | ✅ 완료 (명세 v0.2.5: 가장자리 구조물 제외, 자세 불연속 15 m/s) |
| M6 | HRTF 추출 + 바이노럴 렌더러 | −60°~+60° 스윕 WAV, 클릭 없음, 헤드폰 방향 확인 | ✅ 완료 (명세 v0.2.6: SADIE II D1, 헤드폰 청취 확인) |
| M7 | 앱 통합: 스레드, 실시간·재생 모드, 오디오 출력, 디버그 오버레이, 실행 로그 | S02 재생에서 의자 방향으로 소리, F8 기록 | ⏳ |
| M8 | 오프라인 재생 테스트 + Python 분석 도구 | 합성 세션 지표 ≈ 0, 실제 세션 전 지표 산출 | ⏳ |
| M9 | 사용자 모드 UI, 알림음·진동, 오디오 포커스 | TalkBack 상태에서 시작·일시정지·종료 | ⏳ |
| M10 | 전체 장면 녹화, 대표점·복셀 비교, 10분 지속 | 비교표와 파라미터 조정안 | ⏳ |

### 6.2 선택 (필수 완료 후, 측정 근거가 있을 때)

O1 에뮬레이터 재생 · O2 깊이 신경망 보조 · O3 IMU 외삽 · O4 동시 음원 2~3개 · O5 C++ 오디오 출력 · O6 설정 프리셋

---

## 7. 산출물

### 7.1 저장소 안 (git으로 관리)

| 산출물 | 위치 | 생기는 시점 |
|---|---|---|
| 구현 명세 | `docs/MVP_SPEC.md` | M0 전 |
| 결정 기록 | `docs/DECISIONS.md` | 계속 |
| 세션 형식·스파이크 결과 | `docs/FORMAT.md` | M1, G1 |
| 라이선스 기록 | `docs/LICENSES.md` | 의존성 추가 시 |
| 설정 기본값 | `app/src/main/assets/config/default.json` | M0~ |
| 앱용 HRIR 바이너리 | `app/src/main/assets/hrtf/` | M6 (데이터셋 승인 후) |
| 합성 장면 테스트 | `core/src/test/.../synth/` | M2~ |
| 비교 보고서 (Markdown) | `report.py` 출력 | M8, M10 |

### 7.2 저장소 밖 (git 제외)

| 산출물 | 만드는 법 | 보관 |
|---|---|---|
| 디버그 APK | `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/` | 마일스톤 태그의 GitHub Release에 첨부 |
| 녹화 세션 | 앱 녹화 → `adb pull` → `data/sessions/<세션ID>/` | 팀 공유 드라이브 (위치: TODO). 대표 세션 경량본은 저장소 `testdata/sessions/` |
| 정답 주석 | `data/sessions/<세션ID>/annotations/obstacles.json` | 세션과 함께 |
| 실행 로그·지표 | `run_log/`, `metrics.json` | 세션과 함께 |
| 시연 영상 | scrcpy 화면 녹화 | 팀 공유 드라이브 |

세션 폴더 이름(`<yyyyMMdd_HHmmss>_<장면ID>`)은 바꾸지 않는다. `docs/FORMAT.md`와 보고서가 이 이름으로 세션을 가리킨다.
