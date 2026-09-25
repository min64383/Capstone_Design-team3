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
