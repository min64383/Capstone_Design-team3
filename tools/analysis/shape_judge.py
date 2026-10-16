"""군집 모양 판정표 (M23): 변형별 실행 로그 폴더 → 세션 × 변형 군집 모양(cluster_eval)과 완료 기준 요약(Markdown).

    python shape_judge.py <기준 변형 폴더> <변형 폴더> ...     (예: data/sweeps/m23/CUR data/sweeps/m23/G ...)

폴더마다 <세션ID>/metrics.json(sweep.py 출력, `clusterEval` 포함)을 읽는다. 첫 폴더가 기준(CUR)이다.
완료 기준(M23 설계 표 7): 서 있는 캐리어(정답 `suitcase`) 세션의 뒤 늘어남 중앙 ≤ 0.10 m·크기 맞음 ≥ 50% 세션 수, 캐리어 세션
(누운 캐리어 포함) 중 갈라짐 > 5% 세션 수, 안전(STOP 누락, 첫 STOP이 기준보다 0.1 m 넘게 늦음), 확인용 성적표 불합격 수, 지도 단계 p50.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from report import fmt, table
from scorecard import CONFIG, FAIL, judge, role_of

BACK_M, FIT, SPLIT, LATE_STOP_M = 0.10, 0.5, 0.05, 0.1
JUMP, OTHER = 0.014, 0.05  # 흔들림 문턱(정답 깊이 상한 수준, M22 설계 표 1), 음원이 다른 것인 단계 문턱(M22 표 9)
STANDING, LYING = "suitcase", "suitcase_lying"


def load_dir(d: Path) -> dict[str, dict]:
    return {p.parent.name: json.loads(p.read_text(encoding="utf-8")) for p in sorted(d.glob("*/metrics.json"))}


def carrier(m: dict) -> tuple[str, dict] | None:
    ce = m.get("clusterEval") or {}
    for name in (STANDING, LYING):
        if name in ce:
            return name, ce[name]
    return None


def track_summary(name: str, runs: dict[str, dict]) -> list:
    """군집·추적·음원(M22, `clusterTrack`): 캐리어 세션의 근처 다른 출력 합, track id 1개·바뀜 0 세션, 흔들림 ≤ [JUMP] 세션,
    물체 세션 중 음원이 다른 것인 단계 > [OTHER]인 세션. 옛 재생(`mainCluster` 없음)은 –."""
    ct = [(n, c) for m in runs.values() for n, c in ((m.get("clusterTrack") or {}).items())]
    car = [c for n, c in ct if n in (STANDING, LYING)]
    if not ct:
        return [name, "–", "–", "–", "–"]
    return [name, str(sum(c["nearbyOtherIds"] for c in car)),
            f"{sum(c['trackIds'] == 1 and c['trackSwitches'] == 0 for c in car)}/{len(car)}",
            f"{sum(c['jumpFraction'] is not None and c['jumpFraction'] <= JUMP for c in car)}/{len(car)}",
            f"{sum((c['sourceOtherFraction'] or 0) > OTHER for _, c in ct)}/{len(ct)}"]


def summary(name: str, runs: dict[str, dict], base: dict[str, dict], cfg: dict) -> list:
    shapes = [c for m in runs.values() if (c := carrier(m))]
    standing = [c for n, c in shapes if n == STANDING]
    back = sum(1 for c in standing if c["backP50M"] is not None and c["backP50M"] <= BACK_M)
    fit = sum(1 for c in standing if c["fitFraction"] >= FIT)
    split = sum(1 for _, c in shapes if c["splitFraction"] > SPLIT)
    missed = sum(m.get("missedStop") or 0 for m in runs.values())
    late = 0
    for s, m in runs.items():
        for o, d in (m.get("firstStopDistanceM") or {}).items():
            d0 = ((base.get(s) or {}).get("firstStopDistanceM") or {}).get(o)
            if d0 is not None and (d is None or d < d0 - LATE_STOP_M):
                late += 1
    fails = sum(judge(m, it, cfg)[0] == FAIL for m in runs.values() if role_of(m, cfg) == "확인용" for it in cfg["items"])
    maps = sorted(m["stageMs"]["map"]["p50"] for m in runs.values() if m.get("stageMs"))
    return [name, f"{back}/{len(standing)}", f"{fit}/{len(standing)}", f"{split}/{len(shapes)}", str(missed), str(late), str(fails),
            fmt(maps[len(maps) // 2]) if maps else "–"]


def main(dirs: list[Path]) -> str:
    cfg = json.loads(CONFIG.read_text(encoding="utf-8"))
    runs = {d.name: load_dir(d) for d in dirs}
    base = runs[dirs[0].name]
    out = ["### 요약", "", table(["변형", f"뒤 늘어남 ≤ {BACK_M} m", f"크기 맞음 ≥ {FIT:.0%}", f"갈라짐 > {SPLIT:.0%}", "STOP 누락",
                                    f"첫 STOP {LATE_STOP_M} m 넘게 늦음", "확인용 불합격 수", "지도 단계 p50 ms(세션 중앙)"],
                                   [summary(v, r, base, cfg) for v, r in runs.items()]), "",
           "### 군집·추적·음원(M22)", "", table(["변형", "근처 다른 출력(캐리어 합)", "track id 1개·바뀜 0", f"흔들림 ≤ {JUMP:.1%}",
                                              f"음원 다른 것 > {OTHER:.0%}(물체)"], [track_summary(v, r) for v, r in runs.items()]), ""]
    sessions = sorted({s for r in runs.values() for s in r if carrier(r[s])}, key=lambda s: (base.get(s, {}).get("scene", ""), s))
    for title, key, f in [("뒤 늘어남 중앙(m)", "backP50M", fmt), ("옆 넘침 중앙(m)", "sideP50M", fmt),
                          ("크기 맞음(단계 %)", "fitFraction", lambda v: f"{100 * v:.0f}"),
                          ("갈라짐(단계 %)", "splitFraction", lambda v: f"{100 * v:.1f}"),
                          ("놓침(단계 %)", "missFraction", lambda v: f"{100 * v:.1f}"),
                          ("밖 칸 중 이번 장 관측(HIT) %", "outsideState", lambda v: f"{100 * v['HIT']:.0f}" if v else "–"),
                          # M24 전 재생에는 BEHIND가 없고 OCCLUDED가 앞면 뒤까지 포함한다
                          ("밖 칸 중 칸 자리 관측(OCCLUDED) %", "outsideState", lambda v: f"{100 * v['OCCLUDED']:.0f}" if v else "–"),
                          ("밖 칸 중 앞면 뒤(BEHIND) %", "outsideState", lambda v: f"{100 * v['BEHIND']:.0f}" if v and "BEHIND" in v else "–"),
                          ("밖 칸 중 시야 밖 %", "outsideState", lambda v: f"{100 * v['OUT_OF_VIEW']:.0f}" if v else "–")]:
        rows = []
        for s in sessions:
            m0 = base.get(s) or next(r[s] for r in runs.values() if s in r)
            row = [s[9:], m0["scene"], role_of(m0, cfg)]
            for r in runs.values():
                c = carrier(r[s]) if s in r else None
                v = c[1].get(key) if c else None
                row.append(f(v) if v is not None else "–")
            rows.append(row)
        out += [f"### {title}", "", table(["세션", "장면", "구분"] + list(runs), rows), ""]
    return "\n".join(out)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")  # Windows 콘솔(cp949)에서도 한글·기호 출력
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    print(main([Path(a) for a in sys.argv[1:]]))
