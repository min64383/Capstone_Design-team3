"""여러 metrics.json → Markdown 비교표 (MVP_SPEC 부록 C, M8).

    python report.py <metrics.json 또는 실행 로그 폴더> ...           → 실행마다 한 줄
    python report.py --group <metrics.json 또는 실행 로그 폴더> ...   → 장면 × 변형마다 한 줄(반복 회차 묶음, M10)

`--group`은 같은 장면·같은 변형의 회차를 묶어 중앙값을 낸다(STOP 누락은 합계). 장면은 metrics.json의 `scene`,
변형은 `variant`(sweep.py가 기록), 없으면 실행 로그 폴더 이름.
"""
from __future__ import annotations

import json
import statistics
import sys
from pathlib import Path

# (머리글, 값 꺼내기, 묶을 때 합칠 방법)
def wall_stat(m: dict, key: str, agg):
    """지도 평가(M13)의 벽마다 값을 [agg]로 하나로(벽이 없으면 None)."""
    w = m.get("mapWalls")
    return agg(v[key] for v in w.values()) if w else None


COLUMNS = [
    ("방향 오차 p95(°)", lambda m: (m.get("directionErrorDeg") or {}).get("p95"), "median"),
    ("과대추정 p95", lambda m: (m.get("overestimate1p5to2p5") or {}).get("p95"), "median"),
    ("구간 일치", lambda m: m.get("bandAgreement"), "median"),
    ("경고 시점 오차(s)", lambda m: m.get("warnTimingErrorS"), "median"),
    ("첫 경고 거리(m)", lambda m: m.get("firstWarnDistanceM"), "median"),
    ("첫 STOP 거리(m)", lambda m: m.get("firstStopDistanceM"), "median"),
    ("STOP 누락", lambda m: m.get("missedStop"), "sum"),
    ("흔들림 std(°)", lambda m: m.get("sourceJitterDegStd"), "median"),
    ("오경보 비율", lambda m: m.get("falseAlarmFraction"), "median"),
    ("합쳐짐 비율", lambda m: m.get("objectMergedFraction"), "median"),
    ("벽 두께 p50(m)", lambda m: wall_stat(m, "thicknessP50M", max), "median"),
    ("벽 앞 치우침 최대(m)", lambda m: wall_stat(m, "frontOffsetP50M", max), "median"),
    ("경고 비율", lambda m: m.get("warnFraction"), "median"),
    ("UNKNOWN 비율", lambda m: (m.get("stateFraction") or {}).get("UNKNOWN", 0.0), "median"),
    ("느린 경로 Hz", lambda m: m.get("slowPathHz"), "median"),
    ("정보 나이 p95(ms)", lambda m: (m.get("infoAgeMs") or {}).get("p95"), "median"),
    ("파이프라인 지연 p95(ms)", lambda m: (m.get("latencyMs") or {}).get("a_pipeline_p95"), "median"),
]


def fmt(v) -> str:
    if v is None:
        return "–"
    if isinstance(v, float):
        return f"{v:.3g}"
    if isinstance(v, dict):
        return ", ".join(f"{k} {fmt(x)}" for k, x in v.items())
    return str(v)


def combine(values: list, how: str):
    """회차 값들을 하나로. 숫자는 중앙값(또는 합), 딕셔너리는 키마다, None은 뺀다."""
    vals = [v for v in values if v is not None]
    if not vals:
        return None
    if all(isinstance(v, dict) for v in vals):
        keys = sorted({k for v in vals for k in v})
        return {k: combine([v.get(k) for v in vals], how) for k in keys}
    if how == "sum":
        return sum(vals)
    return float(statistics.median(vals))


def load(arg: str) -> dict:
    p = Path(arg)
    f = p / "metrics.json" if p.is_dir() else p
    return json.loads(f.read_text(encoding="utf-8"))


def variant_of(m: dict) -> str:
    return m.get("variant") or Path(m["run"]).name


def table(head: list[str], rows: list[list[str]]) -> str:
    lines = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    lines += ["| " + " | ".join(r) + " |" for r in rows]
    return "\n".join(lines)


def main(paths: list[str]) -> str:
    rows = []
    for a in paths:
        m = load(a)
        est = " (정답 추정치)" if (m.get("truth") or {}).get("estimated") else ""
        rows.append([f"{m['session']} / {variant_of(m)}{est}"] + [fmt(get(m)) for _, get, _ in COLUMNS])
    return table(["실행"] + [c for c, _, _ in COLUMNS], rows)


def grouped(paths: list[str]) -> str:
    groups: dict[tuple[str, str], list[dict]] = {}
    for a in paths:
        m = load(a)
        groups.setdefault((m.get("scene") or "?", variant_of(m)), []).append(m)
    rows = []
    for (scene, variant), ms in sorted(groups.items()):
        est = " (정답 추정치)" if any((m.get("truth") or {}).get("estimated") for m in ms) else ""
        rows.append([scene + est, variant, str(len(ms))] + [fmt(combine([get(m) for m in ms], how)) for _, get, how in COLUMNS])
    return table(["장면", "변형", "회차"] + [c for c, _, _ in COLUMNS], rows)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")  # Windows 콘솔(cp949)에서도 한글·기호 출력
    args = sys.argv[1:]
    if not args or args == ["--group"]:
        sys.exit(__doc__)
    print(grouped(args[1:]) if args[0] == "--group" else main(args))
