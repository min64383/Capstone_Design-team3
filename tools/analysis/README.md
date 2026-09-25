# tools/analysis

로그 분석 전용 Python 도구 (MVP_SPEC 부록 C). 앱·core 로직은 여기에 두지 않는다.
스크립트(`align.py`, `metrics.py`, `report.py`, `plots.py`, `extract_hrir.py`)는 M6·M8에서 추가한다.

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r tools/analysis/requirements.txt
```
