# 라이선스 기록

코드·가중치·데이터를 구분해 기록한다 (MVP_SPEC §14-5).

## 코드 (빌드·런타임 의존성)

| 이름 | 버전 | 용도 | 라이선스 | 승인 |
|---|---|---|---|---|
| Android Gradle Plugin | 9.4.0 | app 빌드 | Apache-2.0 | 2026-09-26 (M0) |
| Kotlin (Gradle 플러그인·표준 라이브러리) | 2.4.20 | core·app | Apache-2.0 | 2026-09-26 (M0) |
| Gradle (래퍼) | 9.8.0 | 빌드 | Apache-2.0 | 2026-09-26 (M0) |
| JUnit Jupiter / Platform | 6.1.3 | core 테스트 전용 | EPL-2.0 | 2026-09-26 (M0) |
| ARCore SDK (`com.google.ar:core`) | 1.56.0 | app: 자세·깊이·녹화 | SDK 바이너리(AAR): **ARCore Additional Terms of Service** (https://developers.google.com/ar/develop/terms) + Google APIs ToS. 오픈소스 아님 | 2026-09-26 (M1) |
| numpy | 2.4.6 (venv 기준, 미고정) | tools/analysis | BSD-3-Clause | 2026-09-26 (M0) |
| pandas | 3.0.6 (venv 기준, 미고정) | tools/analysis | BSD-3-Clause | 2026-09-26 (M0) |
| matplotlib | 3.11.2 (venv 기준, 미고정) | tools/analysis | Matplotlib License (PSF 계열) | 2026-09-26 (M0) |
| Pillow | 12.3.0 (matplotlib 전이 의존성) | tools/analysis: PNG·JPEG 읽기(spike_check.py) | MIT-CMU (HPND) | 2026-09-26 (M1, 전이 의존성 사용) |

### ARCore 약관 메모

- 앱을 배포(사용자 평가 포함)할 때는 앱 안내문에 "ARCore 기능을 포함하며 Google 서비스 약관·개인정보처리방침이 적용된다"는 고지가 필요하다(ARCore Additional Terms). M9 사용자 모드에서 반영.
- 공식 샘플(hello_ar, Apache-2.0) 코드는 복사하지 않았다. `render/BackgroundRenderer.kt`는 샘플의 방식(OES 텍스처 + `transformCoordinates2d`)을 참고해 새로 작성.

## 모델 가중치

없음.

## 데이터 (HRTF·데이터셋)

없음. HRTF는 M6 시작 전 사용자 승인 후 기록.
