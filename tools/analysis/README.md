# tools/analysis

로그 분석 전용 Python 도구 (MVP_SPEC 부록 C). 앱·core 로직은 여기에 두지 않는다.

| 스크립트 | 마일스톤 | 용도 |
|---|---|---|
| `spike_check.py` | M1 | 녹화 세션 1개로 스파이크 F1~F7 수치 출력 (docs/FORMAT.md) |
| `align.py` | M8 | 보행선 정렬(§10.3) → `align.json` |
| `metrics.py` | M8 | 지표(§10.2) → `metrics.json` |
| `report.py` | M8 | 여러 `metrics.json` → Markdown 비교표 |
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

환경:

```powershell
uv venv --python 3.11 .venv
uv pip install --python .venv\Scripts\python.exe -r tools/analysis/requirements.txt
.venv\Scripts\python.exe tools/analysis/spike_check.py data/sessions/<세션ID>
```
