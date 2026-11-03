# 라이선스 기록

코드·가중치·데이터를 구분해 기록한다 (MVP_SPEC §14-5).

## 코드 (빌드·런타임 의존성)

| 이름 | 버전 | 용도 | 라이선스 | 승인 |
|---|---|---|---|---|
| Android Gradle Plugin | 9.4.0 | app 빌드 | Apache-2.0 | 2026-09-26 (M0) |
| Kotlin (Gradle 플러그인·표준 라이브러리) | 2.4.20 | core·app | Apache-2.0 | 2026-09-26 (M0) |
| Gradle (래퍼) | 9.8.0 | 빌드 | Apache-2.0 | 2026-09-26 (M0) |
| JUnit Jupiter / Platform | 6.1.3 | core·app 테스트 전용(앱에 들어가지 않음) | EPL-2.0 | 2026-09-26 (M0), app 2026-10-30 (M45) |
| ARCore SDK (`com.google.ar:core`) | 1.56.0 | app: 자세·깊이·녹화 | SDK 바이너리(AAR): **ARCore Additional Terms of Service** (https://developers.google.com/ar/develop/terms) + Google APIs ToS. 오픈소스 아님 | 2026-09-26 (M1) |
| numpy | 2.4.6 (requirements.txt 고정, M41) | tools/analysis | BSD-3-Clause | 2026-09-26 (M0) |
| pandas | 3.0.6 (requirements.txt 고정, M41) | tools/analysis | BSD-3-Clause | 2026-09-26 (M0) |
| matplotlib | 3.11.2 (requirements.txt 고정, M41) | tools/analysis | Matplotlib License (PSF 계열) | 2026-09-26 (M0) |
| h5py | 3.16.0 (requirements.txt 고정, M41) | tools/analysis: SOFA(HDF5) 읽기(extract_hrir.py) | BSD-3-Clause | 2026-09-29 (M6) |
| Pillow | 12.3.0 (requirements.txt 고정, M41. 전에는 matplotlib 전이 의존성) | tools/analysis: PNG·JPEG 읽기(spike_check.py) | MIT-CMU (HPND) | 2026-09-26 (M1, 전이 의존성 사용) |
| PyAV (`av`) | 18.1.0 (requirements.txt 고정, M41) | tools/analysis: ARCore 녹화 MP4의 H.264 영상 디코딩(mp4_rgb.py). 앱에는 들어가지 않음 | BSD-3-Clause. 휠에 FFmpeg 8.1.2 라이브러리 동봉: **LGPL v3 이상** 빌드(`--enable-version3`, GPL·nonfree 아님, `av._core.library_meta`로 확인) | 2026-10-10 (M19) |

### ARCore 약관 메모

- 앱을 배포(사용자 평가 포함)할 때는 앱 안내문에 "ARCore 기능을 포함하며 Google 서비스 약관·개인정보처리방침이 적용된다"는 고지가 필요하다(ARCore Additional Terms). M9에서 빠졌던 것을 M40에서 반영(A1 TR7): 사용자 모드 시작 화면 아래에 늘 보이고, 처음 시작하기 전까지는 TalkBack이 화면 설명과 함께 읽는다(`UserTexts.ARCORE_NOTICE`). M45부터 설정 > 정보 > ARCore 고지에서도 언제든 볼 수 있고, 같은 곳의 "오픈소스 라이선스"가 앱에 든 SADIE II(고지·Apache 2.0 전문)·Kotlin 표준 라이브러리·ARCore 약관을 알린다. 약관 원문(https://developers.google.com/ar/develop/terms, 2026-10-27 확인)은 앱의 최종 사용자 약관에서 알리라고 하며 문구는 정하지 않는다: 앱 밖 약관·안내문(사용자 평가 동의서 등)을 만들면 같은 문장을 넣는다.
- 공식 샘플(hello_ar, Apache-2.0) 코드는 복사하지 않았다. `render/BackgroundRenderer.kt`는 샘플의 방식(OES 텍스처 + `transformCoordinates2d`)을 참고해 새로 작성.

## 모델 가중치

M37 학습형 깊이(재생 전용, 앱에 들어가지 않음). 가중치·코드는 `data/models/`(저장소 밖)에 두고 아래 커밋·해시로 고정한다. 실행 중 원격 코드는 받지 않는다(`torch.hub`는 로컬 클론만, 가중치는 `weights_only`로만 연다). 승인 2026-10-31(M37 설계).

| 이름 | 파일(SHA-256 앞 16자) | 코드(고정 커밋) | 라이선스 | 주의 |
|---|---|---|---|---|
| MiDaS v2.1 small | `midas_v21_small_256.pt`(70d6b9c891758c67), github.com/isl-org/MiDaS 릴리스 v2_1 | isl-org/MiDaS 4545977(MIT), 백본 rwightman/gen-efficientnet-pytorch 771ce08(Apache-2.0) | MIT | 백본 ImageNet 가중치는 쓰지 않음(경로를 주어 내려받지 않게) |
| ZipDepth base_npu | `zipdepth_base_npu.pth`(627c04fda584133e), 저장소 안 checkpoints/ | fabiotosi92/ZipDepth 91f3fd2(MIT) | MIT | 학습용 가짜 정답을 Depth Anything V2 Large(CC-BY-NC-4.0)로 만들었다. 공모전·상업 이용 전에 다시 확인 |
| VI-Depth ScaleMapLearner(midas_small, VOID 1500점) | `sml_model.dpredictor.midas_small.nsamples.1500.ckpt`(fd29689f5e1ea77d), github.com/isl-org/VI-Depth 릴리스 v1 → `extract_sml_state.py`로 꺼낸 `sml_midas_small_1500_state.pt`(652bcee03dd00725) | isl-org/VI-Depth 2b4cf6e(MIT, Intel 관리 중단) | MIT | 학습 데이터 VOID의 이용 조건 확인 안 함(VERIFY). Lightning 체크포인트는 빈 클래스 대입으로 텐서만 꺼냄(weights_only 유지) |

PC 실행 환경 `.venv-learned`(tools/analysis/requirements-learned.txt): PyTorch 2.15.0+cpu·torchvision 0.29.2(BSD-3), timm 1.0.30(Apache-2.0), OpenCV 5.0.0.93 headless(Apache-2.0), SciPy 1.18.1(BSD-3), Pillow 13.0.0, PyAV 19.0.2, NumPy 2.5.4, matplotlib 3.11.2(ZipDepth 코드가 가져옴). 기기 시간 측정용 TFLite 변환(onnx 1.23.2·onnx2tf 1.28.8·TensorFlow 2.21.0, Apache-2.0·MIT)과 Google TFLite `benchmark_model`(Apache-2.0)은 작업 폴더에서만 쓰고 저장소에 넣지 않았다.

## 데이터 (HRTF·데이터셋)

| 이름 | 사용 부분 | 라이선스 | 조건 | 승인 |
|---|---|---|---|---|
| SADIE II Database, subject D1 (Neumann KU100), University of York | `D1_48K_24bit_256tap_FIR_SOFA.sofa`의 수평면(고도 0°) 400개 방위각 → `app/src/main/assets/hrtf/sadie2_d1_48k.hrir` | Apache License 2.0 (Copyright 2018, University of York) | 라이선스 사본 첨부(`assets/hrtf/LICENSE-Apache-2.0.txt`), 변경 고지·출처(`assets/hrtf/NOTICE.txt`), 학술 사용 시 DOI 10.3390/app8112029 인용 | 2026-09-29 (M6) |

원본 SOFA는 `data/hrtf/`(git 제외)에 둔다. 다운로드: https://zenodo.org/records/12092466/files/D1_HRIR_SOFA.zip
