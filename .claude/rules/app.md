---
paths:
  - "app/**"
---
# app 모듈 규칙
- ARCore API는 공식 문서·샘플로 확인 후 사용. 폐기된 Sceneform 사용 금지
- ARCore 이미지 객체는 획득한 스레드에서 즉시 복사 후 닫는다
- 안내 로직은 core에 두고 app은 변환·스레드·UI만 담당
- GL 스레드에서 무거운 처리를 하지 않는다