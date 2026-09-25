# 결정 기록

형식: `날짜 · 마일스톤 · 결정 — 이유`

- 2026-09-26 · M0 · 빌드 도구: AGP 9.4.0, Gradle 9.8.0(래퍼, SHA-256 고정), Kotlin 2.4.20 — 각 공식 릴리스 페이지 기준 현재 안정 버전. AGP 9.4.0의 최소 Gradle은 9.6.0
- 2026-09-26 · M0 · JDK: Android Studio 내장 JBR 25로 Gradle 실행, 바이트코드 대상은 Java 17 — AGP 9.4.0 요구 JDK 17 이상
- 2026-09-26 · M0 · app은 AGP 9 내장 Kotlin 사용, `org.jetbrains.kotlin.android` 미적용 — AGP 9 공식 이전 안내
- 2026-09-26 · M0 · compileSdk/targetSdk 37, minSdk 26 — AGP 9.4.0 최대 API 37, 설치된 SDK platform 37
- 2026-09-26 · M0 · app은 AndroidX 없이 `android.app.Activity`로 시작 — M0에 필요 없음. UI 요구가 생기면 승인 후 추가
- 2026-09-26 · M0 · core 테스트: JUnit Jupiter 6.1.3(BOM) — core 테스트 전용, 승인됨
- 2026-09-26 · M0 · 설정의 유일한 원본은 `app/src/main/assets/config/default.json`. `Config.kt`에는 기본값이 없고, core 테스트가 Gradle 시스템 속성으로 같은 파일을 읽어 §12 표와 비교 — 수치 하드코딩 금지 원칙, 값이 두 곳에 갈라지는 것 방지
- 2026-09-26 · M0 · 설정 로더는 키 누락·미지의 키·타입 불일치·범위 위반을 모두 오류로 처리. 실험 패널 덮어쓰기는 부분 JSON 깊은 병합 후 동일 검증 — 오타 난 키가 조용히 무시되는 것 방지
- 2026-09-26 · M0 · JSON 파서는 core에 직접 구현(`MiniJson`, 중복 키 오류) — core 외부 라이브러리 금지 원칙
- 2026-09-26 · M0 · `Mat4`는 행 우선 저장, 이름 규약 `aFromB` — M2에서 연산 추가
- 2026-09-26 · M0 · `AlertKind` = START, READY, PAUSE, UNKNOWN — §7.6 상태 알림음 4종(시작·준비 완료·정지·확인 불가)
- 2026-09-26 · M0 · 저장소 루트는 `capstone/`(명세 §4의 `walkassist/`에 해당), Gradle 루트 프로젝트 이름은 `walkassist`, 원격은 팀 저장소 `min64383/Capstone_Design-team3`
- 2026-09-26 · M0 · 기준 기기를 연결된 SM-G977N(Galaxy S10 5G 국내판, Exynos 9820 확인, Android 12)으로 확정하고 명세 §0·§2.1·§3 갱신. 명세의 "S10"은 이 기기 — 사용자 결정
- 2026-09-26 · M1 · ARCore SDK 1.56.0(Google Maven 최신) 추가 — 사용자 승인
- 2026-09-26 · M1 · 깊이 모드: AUTOMATIC 지원 시 AUTOMATIC(일반·원시 깊이 모두 획득), 아니면 RAW_DEPTH_ONLY — F6 비교
- 2026-09-26 · M1 · 형식 v0에 `raw_depth/`, `frames.csv`의 화면 방향 자세 열(`dtx..dqw`), `sysElapsedNs` 열 추가 — F2·F5·F6 확인용, 사용자 승인. v1에서 존치 여부 결정
- 2026-09-26 · M1 · 녹화 저장: 자세 행은 큐(모든 프레임 기록, §8.1), 파일은 슬롯 1개(차 있으면 이번 프레임 파일을 건너뛰고 행에 이름을 쓰지 않음) — 행에 적힌 파일은 항상 존재. 녹화는 안내 경로가 아니라 "최신 값" 원칙 대신 기록 완전성을 우선
- 2026-09-26 · M1 · 일반 깊이는 새 시각의 이미지만 저장 — 같은 깊이의 재투영 반복을 파일로 중복 저장하지 않기 위해. F5에서 실제 갱신 빈도 확인
- 2026-09-26 · M1 · 16비트 PNG는 core `Png16`로 직접 인코딩(Sub 필터 + Deflater) — Android Bitmap은 16비트 흑백 불가, 외부 라이브러리 금지
- 2026-09-26 · M1 · 설정 키 `record.deviceLogIntervalS`(1.0) 추가, 명세 §12 표 갱신 — device.csv 주기를 하드코딩하지 않기 위해
- 2026-09-26 · M1 · 녹화 화면은 세로 고정 — 녹화마다 화면·센서 방향 관계를 같게 두어 F2·F3 해석을 단순화
- 2026-09-26 · M1 · 녹화 시작·정지는 UI가 요청만 하고 GL 스레드가 다음 프레임에서 처리 — ARCore 세션을 한 스레드에서만 다룸. onPause에서는 GLSurfaceView.onPause 뒤 UI 스레드에서 정지
- 2026-09-26 · M1 · 분석 venv는 uv로 Python 3.11(명세 버전) `.venv` 생성
- 2026-09-26 · M0 수정 · `gradle.properties`에 BOM이 있어 첫 줄 `org.gradle.jvmargs`가 무시되던 문제 수정(PowerShell Set-Content 부작용), Metaspace 상한 1g 추가
- 2026-09-26 · M1 · ARCore 이미지 복사는 행 단위 일괄 읽기 — 픽셀 단위 복사가 GL 스레드를 최대 190 ms 막는 것을 계측으로 확인(§14-7). 녹화 중 29.5 fps 회복
- 2026-09-26 · M1 · GL 스레드 구간별 소요 시간을 1초마다 logcat(`gl timing`)에 요약 — 병목 확인용으로 유지, 형식은 M7 실행 로그에서 재검토
- 2026-09-26 · M1 · `meta.json`에 `elapsedMinusMonotonicNs` 추가(없으면 null로 읽음) — F5에서 `Frame.getTimestamp()` 시간 기준 판별용
- 2026-09-26 · M1 · (예비) 깊이 K는 텍스처 K를 크기 비율로 환산 — 깊이 160x90이 텍스처 16:9와 같고 CPU K 환산은 비등방. 벽 거리 촬영(F4)으로 확정 후 v1에 명시
