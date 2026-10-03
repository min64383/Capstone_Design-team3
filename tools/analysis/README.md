# tools/analysis

로그 분석 전용 Python 도구 (MVP_SPEC 부록 C). 앱·core 로직은 여기에 두지 않는다.

| 스크립트 | 마일스톤 | 용도 |
|---|---|---|
| `spike_check.py` | M1 | 녹화 세션 1개로 스파이크 F1~F7 수치 출력 (docs/FORMAT.md) |
| `align.py` | M8 | 보행선 정렬(§10.3) → `align.json` |
| `metrics.py` | M8, M10, M11 | 지표(§10.2) → `metrics.json`. `--run <실행 로그>`면 세션 없이(사용자 모드 T01: 1분마다 UNKNOWN·주기·발열). 정답 v2(`distanceFrom`, `kind`), 단계별 처리 시간(`stage_timing.csv` → `stageMs`) |
| `report.py` | M8, M10 | 여러 `metrics.json` → Markdown 비교표. `--group`이면 장면 × 변형마다 회차 묶음 |
| `sweep.py` | M10 | 변형 목록(`sweeps/*.json`) × 세션마다 오프라인 재생 → `metrics.json` |
| `plots.py` | M8 | 시계열·지연·위에서 본 그림, `--at 초`로 그 시각 RGB와 나란히 |
| `test_analysis.py` | M8 | 합성 오프라인 재생으로 분석 도구 자체 점검 |
| `extract_hrir.py` | M6 | SOFA HRTF → 앱용 수평면 HRIR 바이너리(`assets/hrtf/`) |

환경 (PowerShell, 저장소 루트, Python 3.11):

M8 흐름 (정답 `annotations/obstacles.json` 형식은 docs/FORMAT.md):

```powershell
./gradlew :core:replay -Psession=data/sessions/<세션ID>      # → core/build/replay/<세션ID>/default/
cd tools/analysis
..\..\.venv\Scripts\python.exe metrics.py ../../data/sessions/<세션ID> ../../core/build/replay/<세션ID>/default
..\..\.venv\Scripts\python.exe plots.py   ../../data/sessions/<세션ID> ../../core/build/replay/<세션ID>/default --at 4 7.5
..\..\.venv\Scripts\python.exe report.py  ../../core/build/replay/*/default
./gradlew :core:test; ..\..\.venv\Scripts\python.exe test_analysis.py   # 자체 점검
```

M10 흐름 (비교 실험, 지속 동작):

```powershell
cd tools/analysis
# 변형 × 세션 → ../../data/sweeps/m10/<변형>/<세션ID>/metrics.json (있는 조합은 건너뜀)
..\..\.venv\Scripts\python.exe sweep.py sweeps/m10.json ../../data/sweeps/m10 ../../data/sessions/<세션ID> ...
..\..\.venv\Scripts\python.exe report.py --group ../../data/sweeps/m10/*/*     # 장면 × 변형 비교표
# 사용자 모드 실행 로그(세션 없음): 1분마다 UNKNOWN 비율·느린 경로 주기·스냅샷 나이·발열
..\..\.venv\Scripts\python.exe metrics.py --run ../../data/runs/<시각>
```

장면 ID는 정답 파일의 `"scene"`이 우선이다(폴더 이름의 장면 ID가 틀린 녹화).

환경:

```powershell
uv venv --python 3.11 .venv
uv pip install --python .venv\Scripts\python.exe -r tools/analysis/requirements.txt
.venv\Scripts\python.exe tools/analysis/spike_check.py data/sessions/<세션ID>
```
