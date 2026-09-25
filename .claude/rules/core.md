---
paths:
  - "core/**"
---
# core 모듈 규칙
- Android·ARCore import 금지. 외부 라이브러리 추가 금지(승인 시 예외)
- 모든 테스트는 합성 장면(`core/src/test/.../synth/`)으로 작성
- 단위는 이름에 포함: M, Ns, Deg, Mm
- 좌표는 월드(W, +Y 위)와 카메라(C_cv: +Y 아래, +Z 앞)만 다룬다