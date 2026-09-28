# WalkAssist — 시각장애인 보행 보조 앱 MVP

구현 기준은 `docs/MVP_SPEC.md`(v0.2.3)이다. 작업 전 해당 마일스톤(§13)과 관련 절을 읽는다.
명세와 코드가 충돌하면 명세를 따르고, 명세가 틀렸다고 판단되면 멈추고 사용자에게 제안한다.

## 구조
- `core/`: 순수 Kotlin(JVM). 알고리즘 전부. PC에서 테스트
- `app/`: Android 앱. ARCore·오디오·UI
- `tools/analysis/`: Python. 로그 분석 전용
- `prototypes/<언어>/<모듈>/`: core 알고리즘 프로토타입(기본 Python). 최종 구현은 Kotlin core로 이식하고 README §4.8 규칙을 따른다

## 명령 (PowerShell)
- core 테스트: `./gradlew :core:test`
- 앱 설치: `./gradlew :app:installDebug`
- 로그: `adb logcat -s WalkAssist`

## 반드시 지킬 원칙
- 현재 시각 이후의 프레임·자세를 절대 사용하지 않는다 (보간 금지, 과거 값만)
- 스레드 간 전달은 큐가 아니라 최신 값 교체
- 정보 나이가 허용치를 넘으면 음원을 재생하지 않는다
- 무음을 "장애물 없음"으로 안내하거나 기록하지 않는다
- 시야 밖 복셀은 감쇠하지 않는다
- 수치는 하드코딩하지 않고 설정에서 읽는다

## 작업 방식
- 한 번에 한 마일스톤. 시작 전 파일·클래스 목록과 테스트 계획을 먼저 제시
- 새 의존성·모델·데이터셋·HRTF 파일은 사용자 승인 후 추가, `docs/LICENSES.md`에 기록
- 결정 사항은 `docs/DECISIONS.md`에 한 줄씩 기록
- 확인하지 못한 API 사양은 `// VERIFY:` 주석으로 표시하고 보고