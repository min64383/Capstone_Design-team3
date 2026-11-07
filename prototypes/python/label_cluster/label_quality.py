"""M56 A-2 칸 라벨 품질·A-4 ε같은 유도(설계 보완판 표 2·4, 재생 voxel_labels.csv.gz 필요: -PlabelDump=true).

    python label_quality.py <재생 폴더>...

A-2: 정답 물체 자리(실내 상자·T 정지 물체 = plike_check.regions, T 사람 = 정답 경로 0.8 m 안, 카메라 3 m 안 장) 점유 칸의 과반 라벨 몫.
A-4: 사람이 카메라 3 m 안인 장마다 사람 자리의 사람 라벨 칸을 모두 잇는 가장 작은 수평 틈(최소 신장 트리의 가장 긴 변). ε같은 = 그 최댓값.
     위 끝: 서로 다른 정답 물체(정지 물체끼리, 사람 대 정지 물체)의 같은 라벨 칸 사이 가장 작은 수평 틈 중 0.15 m(cluster.epsM)를 넘는 것.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "motion_judge"))
import stage0 as S  # noqa: E402
from plike_check import NAMES, regions  # noqa: E402

PERSON, ZONE_M, NEAR_M = 10, 0.8, 3.0


def mst_max_edge(xz: np.ndarray) -> float:
    """수평 점들의 최소 신장 트리에서 가장 긴 변(Prim, 점 수가 작다)."""
    n = len(xz)
    if n < 2:
        return 0.0
    d = np.hypot(xz[:, None, 0] - xz[None, :, 0], xz[:, None, 1] - xz[None, :, 1])
    seen = np.zeros(n, bool)
    seen[0] = True
    best = d[0].copy()
    longest = 0.0
    for _ in range(n - 1):
        b = np.where(seen, np.inf, best)
        j = int(np.argmin(b))
        longest = max(longest, float(b[j]))
        seen[j] = True
        best = np.minimum(best, d[j])
    return longest


def min_gap(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.min(np.hypot(a[:, None, 0] - b[None, :, 0], a[:, None, 1] - b[None, :, 1])))


def analyze(run: Path, eps: float) -> tuple[dict, list, list]:
    session, cfg = S.session_of(run), S.load_config(run)
    vs = cfg["map"]["voxelSizeM"]
    v = pd.read_csv(run / "voxel_labels.csv.gz")
    v["x"], v["y"], v["z"] = (v.ix + 0.5) * vs, (v.iy + 0.5) * vs, (v.iz + 0.5) * vs
    fr = S.load_frames(session)
    cam = fr.drop_duplicates("depthTNs").set_index("depthTNs")[["tx", "ty", "tz"]]
    regs = regions(run, session)
    ev = S.E.load_events(session) or {}
    movers = [S.E.moving_track(o) for o in ev.get("objects", []) if o["kind"] == "moving" and o.get("added") != "check"]
    counts: dict = {}  # 물체 이름 → 라벨별 칸 수(-1 = 과반 없음)
    gaps, bounds = [], []
    for t, g in v.groupby("tCaptureNs"):
        if t not in cam.index:
            continue
        c = cam.loc[t]
        p = g[["x", "y", "z"]].to_numpy()
        lab = g.label.to_numpy()
        members = []  # (이름, 칸 마스크)
        for name, inside, _ in regs:
            m = inside(p)
            if m.any():
                members.append((name, m))
        for tr in movers:
            q = tr(t / 1e9)
            if q is None:
                continue
            pos = q[0]
            if np.hypot(pos[0] - c.tx, pos[1] - c.tz) > NEAR_M:
                continue
            m = np.hypot(p[:, 0] - pos[0], p[:, 2] - pos[1]) <= ZONE_M
            if m.any():
                members.append(("사람", m))
                pm = m & (lab == PERSON)
                if pm.sum() >= 2:
                    gaps.append(mst_max_edge(p[pm][:, [0, 2]]))
        for name, m in members:
            cc = counts.setdefault(name, {})
            for l, n in zip(*np.unique(lab[m], return_counts=True)):
                cc[int(l)] = cc.get(int(l), 0) + int(n)
        for i, (na, ma) in enumerate(members):
            for nb, mb in members[i + 1:]:
                for l in set(lab[ma]) & set(lab[mb]):
                    if l < 1:
                        continue  # 과반 없음·라벨 없음은 같은 라벨로 보지 않음
                    a, b = p[ma & (lab == l)][:, [0, 2]], p[mb & (lab == l)][:, [0, 2]]
                    gap = min_gap(a, b)
                    if gap > eps:
                        bounds.append(dict(session=session.name[9:15], a=na, b=nb, label=NAMES[l], gapM=gap))
    return counts, gaps, bounds


def main(runs: list[Path]) -> None:
    eps = 0.15
    allgaps, allbounds = [], []
    for run in runs:
        counts, gaps, bounds = analyze(run, eps)
        allgaps += gaps
        allbounds += bounds
        for name, cc in counts.items():
            n = sum(cc.values())
            top = sorted(cc.items(), key=lambda kv: -kv[1])[:3]
            print(f"{run.name[9:15]} {name:16s} 칸 {n:6d}: " + ", ".join(f"{'과반 없음' if l < 0 else NAMES[l]} {k / n:.0%}" for l, k in top))
    if allgaps:
        g = np.array(allgaps)
        print(f"A-4 사람 조각을 모두 잇는 틈: 장 {len(g)}, 중앙값 {np.median(g):.3f}, p90 {np.percentile(g, 90):.3f}, p99 {np.percentile(g, 99):.3f}, 최댓값 {g.max():.3f} m")
        print("  틈 > 0.15 m인 장:", int((g > eps).sum()))
    b = pd.DataFrame(allbounds)
    if len(b):
        b.to_csv(Path(__file__).resolve().parents[3] / "data" / "sweeps" / "m56" / "s4" / "bounds.csv", index=False)
        k = b.groupby(["label", "a", "b"]).gapM.min().sort_values()
        print("A-4 위 끝 후보(라벨·물체 쌍별 가장 작은 틈, 작은 순 12개):")
        print(k.head(12).round(3).to_string())
        r = b.loc[b.gapM.idxmin()]
        print(f"A-4 위 끝(다른 정답 물체끼리 같은 라벨 틈 > 0.15 m 중 가장 작은 값): {r.gapM:.3f} m ({r.session} {r.a}–{r.b}, {r.label})")
    else:
        print("A-4 위 끝: 다른 정답 물체끼리 같은 라벨 칸 쌍 없음")


if __name__ == "__main__":
    main([Path(a) for a in sys.argv[1:]])
