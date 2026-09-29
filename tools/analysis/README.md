# tools/analysis

로그 분석 전용 Python 도구 (MVP_SPEC 부록 C). 앱·core 로직은 여기에 두지 않는다.

| 스크립트 | 마일스톤 | 용도 |
|---|---|---|
| `spike_check.py` | M1 | 녹화 세션 1개로 스파이크 F1~F7 수치 출력 (docs/FORMAT.md) |
| `align.py`, `metrics.py`, `report.py`, `plots.py` | M8 | 정렬·지표·보고서 |
| `extract_hrir.py` | M6 | SOFA HRTF → 앱용 수평면 HRIR 바이너리(`assets/hrtf/`) |

환경 (PowerShell, 저장소 루트, Python 3.11):

```powershell
uv venv --python 3.11 .venv
uv pip install --python .venv\Scripts\python.exe -r tools/analysis/requirements.txt
.venv\Scripts\python.exe tools/analysis/spike_check.py data/sessions/<세션ID>
```
