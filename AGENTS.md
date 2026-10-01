<!--
  Codex 등 AGENTS.md를 읽는 에이전트용 지침.
  내용은 CLAUDE.md + .claude/rules/core.md + .claude/rules/app.md 와 같다. 한쪽을 바꾸면 다른 쪽도 같이 바꾼다 (README §4.7).
  Codex는 Git 루트부터 작업 디렉터리까지의 AGENTS.md만 읽으므로, 모듈 규칙도 하위 폴더가 아니라 이 파일에 둔다.
-->
# WalkAssist — 시각장애인 보행 보조 앱 MVP

구현 기준은 `docs/MVP_SPEC.md`(v0.2.10)이다. 작업 전 해당 마일스톤(§13)과 관련 절을 읽는다.
명세와 코드가 충돌하면 명세를 따르고, 명세가 틀렸다고 판단되면 멈추고 사용자에게 제안한다.

## 구조
- `core/`: 순수 Kotlin(JVM). 알고리즘 전부. PC에서 테스트
- `app/`: Android 앱. ARCore·오디오·UI
- `tools/analysis/`: Python. 로그 분석 전용
- `prototypes/<언어>/<모듈>/`: core 알고리즘 프로토타입(기본 Python). 최종 구현은 Kotlin core로 이식하고 README §4.8 규칙을 따른다
- `references/`: 과제 제출 문서(PDF·docx). 구현 참고용이며 명세와 다르면 `docs/MVP_SPEC.md`를 따른다

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

## core 모듈 규칙 (`core/**`를 다룰 때)
- Android·ARCore import 금지. 외부 라이브러리 추가 금지(승인 시 예외)
- 모든 테스트는 합성 장면(`core/src/test/.../synth/`)으로 작성
- 단위는 이름에 포함: M, Ns, Deg, Mm
- 좌표는 월드(W, +Y 위)와 카메라(C_cv: +Y 아래, +Z 앞)만 다룬다

## app 모듈 규칙 (`app/**`를 다룰 때)
- ARCore API는 공식 문서·샘플로 확인 후 사용. 폐기된 Sceneform 사용 금지
- ARCore 이미지 객체는 획득한 스레드에서 즉시 복사 후 닫는다
- 안내 로직은 core에 두고 app은 변환·스레드·UI만 담당
- GL 스레드에서 무거운 처리를 하지 않는다
