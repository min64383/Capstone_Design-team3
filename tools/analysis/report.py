"""여러 metrics.json → Markdown 비교표 (MVP_SPEC 부록 C, M8).

    python report.py <metrics.json 또는 실행 로그 폴더> ...   → 표준 출력
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

COLUMNS = [
    ("방향 오차 p95(°)", lambda m: (m.get("directionErrorDeg") or {}).get("p95")),
    ("과대추정 p95", lambda m: (m.get("overestimate1p5to2p5") or {}).get("p95")),
    ("구간 일치", lambda m: m.get("bandAgreement")),
    ("경고 시점 오차(s)", lambda m: m.get("warnTimingErrorS")),
    ("STOP 누락", lambda m: m.get("missedStop")),
    ("흔들림 std(°)", lambda m: m.get("sourceJitterDegStd")),
    ("오경보 비율", lambda m: m.get("falseAlarmFraction")),
    ("느린 경로 Hz", lambda m: m.get("slowPathHz")),
    ("정보 나이 p95(ms)", lambda m: (m.get("infoAgeMs") or {}).get("p95")),
    ("파이프라인 지연 p95(ms)", lambda m: (m.get("latencyMs") or {}).get("a_pipeline_p95")),
]


def fmt(v) -> str:
    if v is None:
        return "–"
    if isinstance(v, float):
        return f"{v:.3g}"
    if isinstance(v, dict):
        return ", ".join(f"{k} {fmt(x)}" for k, x in v.items())
    return str(v)


def main(paths: list[str]) -> str:
    rows = []
    for a in paths:
        p = Path(a)
        f = p / "metrics.json" if p.is_dir() else p
        m = json.loads(f.read_text(encoding="utf-8"))
        est = " (정답 추정치)" if (m.get("truth") or {}).get("estimated") else ""
        rows.append([f"{m['session']} / {Path(m['run']).name}{est}"] + [fmt(get(m)) for _, get in COLUMNS])
    head = ["실행"] + [c for c, _ in COLUMNS]
    lines = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    lines += ["| " + " | ".join(r) + " |" for r in rows]
    return "\n".join(lines)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    print(main(sys.argv[1:]))
