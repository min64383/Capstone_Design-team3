"""안내 성적표 (M12.2): 여러 metrics.json → 세션별 항목 판정(합격·경계·불합격·해당 없음)과 조정용·확인용 요약(Markdown).

    python scorecard.py <metrics.json 또는 실행 로그 폴더> ...
    python scorecard.py --compare <항목 key,...> <변형별 실행 로그 폴더> ...   → 항목마다 세션 × 변형 표(정답 대입, M12.3)

항목·목표·여유·세션 구분은 `scorecard.json`(IMPROVE_SPEC §9.2). 판정은 세션 단위다(장면당 회차가 1~3개라 묶으면 나쁜 회차가 가려진다).
경계: 값이 목표 ± 여유 안. 여유는 방향이면 그 세션의 정렬 각도 불확실성, 거리면 줄자 오차(`tapeM`). 비율·개수 항목에는 여유가 없다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from report import fmt, load, table

CONFIG = Path(__file__).with_name("scorecard.json")
PASS, BORDER, FAIL, NA = "합격", "경계", "불합격", "–"
SEVERITY = {NA: 0, PASS: 1, BORDER: 2, FAIL: 3}


def verdict(v, op: str, target: float, margin: float = 0.0) -> str:
    if v is None:
        return NA
    if margin > 0 and abs(v - target) <= margin:
        return BORDER
    return PASS if (v <= target if op == "<=" else v >= target) else FAIL


def dig(m: dict, path: str):
    for k in path.split("."):
        m = (m or {}).get(k)
    return m


def zone_objects(m: dict, key: str, min_s: float) -> list[str]:
    """정답이 그 구간(warnZoneS·stopZoneS) 안에 min_s 이상 있었던 정답 이름들(명세 탐지율의 '1 s 이상')."""
    return [n for n, s in (m.get(key) or {}).items() if s >= min_s]


def judge(m: dict, item: dict, cfg: dict) -> tuple[str, str]:
    """(판정, 표시 값). 정답 장애물이 여럿이면 가장 나쁜 것."""
    kind = item.get("kind", "metric")
    if kind == "metric":
        v = dig(m, item["metric"])
        margin = (m.get("truth") or {}).get("align", {}).get("angleUncertaintyDeg", 0.0) if item.get("margin") == "angle" else 0.0
        return verdict(v, item["op"], item["target"], margin), fmt(v)
    pol = m["config"]
    if kind == "missedStop":
        if not zone_objects(m, "stopZoneS", cfg["zoneMinS"]):
            return NA, "–"
        v = m.get("missedStop")
        return verdict(v, item["op"], item["target"]), fmt(v)
    if kind in ("firstWarn", "firstStop"):
        warn = kind == "firstWarn"
        dist = m.get("firstWarnDistanceM" if warn else "firstStopDistanceM") or {}
        worst = (NA, "–")
        for n in zone_objects(m, "warnZoneS" if warn else "stopZoneS", cfg["zoneMinS"]):
            bound = min(m["startDistanceM"][n], pol["warnMaxM"]) if warn else pol["stopM"]
            need = item["target"] * bound
            d = dist.get(n)
            r = (FAIL, f"없음/{need:.2f}") if d is None else (verdict(d, item["op"], need, cfg["tapeM"]), f"{d:.2f}/{need:.2f}")
            if SEVERITY[r[0]] > SEVERITY[worst[0]]:
                worst = r
        return worst
    raise ValueError(f"unknown item kind {kind}")


def role_of(m: dict, cfg: dict) -> str:
    return next((r for r, ids in cfg["roles"].items() if m["session"] in ids), "기타")


REASONS = {"noFloor": "바닥 없음", "noCluster": "군집 없음", "silent": "SILENT", "outOfBand": "구간 밖", "unconfirmed": "추적 미확인",
           "allFiltered": "전부 거름", "noSnapshot": "스냅샷 없음"}


def reasons(m: dict) -> str:
    """경고 놓침 사유별 시간(s), 큰 것부터(M12.3)."""
    r = m.get("missedWarnReasons") or {}
    return ", ".join(f"{REASONS.get(k, k)} {v:.1f}" for k, v in sorted(r.items(), key=lambda kv: -kv[1])) or "–"


def compare(paths: list[str], keys: list[str], cfg: dict) -> str:
    """정답 대입 변형 비교(M12.3): 항목마다 행 = 세션, 열 = 변형(인자 순서), 칸 = 값과 판정. 경고 놓침이면 사유 표도."""
    ms = [load(a) for a in paths]
    variants = list(dict.fromkeys(m.get("variant", "?") for m in ms))
    cell = {(m["session"], m.get("variant", "?")): m for m in ms}
    sessions = sorted({m["session"] for m in ms}, key=lambda s: (cell[next(k for k in cell if k[0] == s)]["scene"], s))
    out = []
    for key in keys:
        it = next(i for i in cfg["items"] if i["key"] == key)
        rows = []
        for s in sessions:
            m0 = cell[next(k for k in cell if k[0] == s)]
            row = [s[9:], m0["scene"], role_of(m0, cfg)]
            for v in variants:
                m = cell.get((s, v))
                j, val = judge(m, it, cfg) if m else (NA, "–")
                row.append(f"{val} {j}" if j != NA else "–")
            rows.append(row)
        out += [f"### {it['name']} ({it['op']} {it['target']})", "", table(["세션", "장면", "구분"] + variants, rows), ""]
        if key == "missedWarn":
            rows = [[s[9:], cell[next(k for k in cell if k[0] == s)]["scene"]] + [reasons(cell[(s, v)]) if (s, v) in cell else "–" for v in variants]
                    for s in sessions]
            out += ["### 경고 놓침 사유(s)", "", table(["세션", "장면"] + variants, rows), ""]
    return "\n".join(out)


def diagnostics(ms: list[dict]) -> str:
    rows = []
    for m in ms:
        de = m.get("distanceErrorM") or {}
        shape = m.get("objectShape") or {}
        width = ", ".join(f"{v['widthM']:.2f}({v['truthWidthM']:.2f})" for v in shape.values()) or "–"
        rows.append([m["session"][9:], m["scene"]] + [fmt((de.get(b) or {}).get("p50")) for b in de] +
                    [fmt(m.get("besideFraction")), fmt(m.get("objectMergedFraction")), width, fmt(m.get("walkSignFlips")), reasons(m)])
    bins = list((ms[0].get("distanceErrorM") or {}).keys()) if ms else []
    return table(["세션", "장면"] + [f"거리 오차 p50 {b} m" for b in bins] + ["옆·뒤 경고", "합쳐짐", "폭 m(정답)", "진행 부호 뒤집힘", "놓침 사유(s)"], rows)


def main(paths: list[str], cfg: dict) -> str:
    ms = sorted((load(a) for a in paths), key=lambda m: (m["scene"], m["session"]))
    items = cfg["items"]
    by_role: dict[str, list[tuple[dict, list[tuple[str, str]]]]] = {}
    for m in ms:
        by_role.setdefault(role_of(m, cfg), []).append((m, [judge(m, it, cfg) for it in items]))
    out = []
    for role, rs in by_role.items():
        rows = [[m["session"][9:], m["scene"]] + [f"{v} {j}" if j != NA else "–" for j, v in js] for m, js in rs]
        out += [f"### {role} ({len(rs)}세션)", "", table(["세션", "장면"] + [it["name"] for it in items], rows), ""]
    summary = []
    for k, it in enumerate(items):
        row = [it["name"], f"{it['op']} {it['target']}"]
        for role, rs in by_role.items():
            js = [j[k][0] for _, j in rs]
            judged = [j for j in js if j != NA]
            row.append(f"{js.count(FAIL)}/{len(judged)}" + (f" (경계 {js.count(BORDER)})" if BORDER in js else ""))
        summary.append(row)
    out += ["### 요약: 불합격 세션 / 판정된 세션", "", table(["항목", "목표"] + list(by_role), summary), ""]
    out += ["### 진단(판정 없음)", "", diagnostics(ms)]
    return "\n".join(out)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")  # Windows 콘솔(cp949)에서도 한글·기호 출력
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cfg = json.loads(CONFIG.read_text(encoding="utf-8"))
    if sys.argv[1] == "--compare":
        print(compare(sys.argv[3:], sys.argv[2].split(","), cfg))
    else:
        print(main(sys.argv[1:], cfg))
