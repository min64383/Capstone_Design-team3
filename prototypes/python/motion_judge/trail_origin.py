"""M57 A-1 흔적 칸의 출처(설계 표 8): 흔적 출력의 칸이 그 사람의 track(후보 포함)이 생긴 뒤, 그 창 안에서 생겼는가.

    python trail_origin.py <재생 폴더>...      (재생은 -PmapInserts=true, gap_trail.py와 같은 흔적 정의)

칸 = 흔적 출력 상자(그 장) 안에서 그때까지 지도에 들어간 칸(map_inserts.csv). 칸이 생긴 시각 = 그 칸의 첫 넣음 시각.
사람 track = motion.csv에서 위치(칼만, 없으면 측정)가 정답 사람 0.8 m 안인 track(종류 무관). 사람 track 탄생 = 그런 행의 첫 시각.
창(근사) = 그 장 사람 track 위치에서 속도 반대쪽으로 τ·|v| 뻗은 선분까지의 수평 거리 ≤ 사람 반폭 0.25 + track.matchRadiusM.
motion.csv에는 창 상자가 없어 근사로 잰다(설계 규칙 2의 소유 적기를 흉내).
분류: before_track(사람 track이 생기기 전) / in_gate(생긴 뒤 창 안 → 소유 규칙이 지움) / out_gate(생긴 뒤 창 밖).
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

import gap_trail as G
import stage0 as S

HALF_WIDTH_M = 0.25
TAU_S = 0.345
VOX = 0.05


def person_rows(mo: pd.DataFrame, tr) -> pd.DataFrame:
    x = mo.copy()
    x["px"] = x.posX.where(np.isfinite(x.posX), x.measX)
    x["pz"] = x.posZ.where(np.isfinite(x.posZ), x.measZ)
    keep = []
    for r in x.itertuples():
        q = tr(r.tCaptureNs / 1e9)
        keep.append(q is not None and np.isfinite(r.px) and np.hypot(r.px - q[0][0], r.pz - q[0][1]) <= G.PERSON_ZONE_M)
    return x[np.array(keep, dtype=bool)]


def in_gate(cx: float, cz: float, rows: pd.DataFrame, margin: float) -> bool:
    for r in rows.itertuples():
        vx = r.velX if np.isfinite(r.velX) else 0.0
        vz = r.velZ if np.isfinite(r.velZ) else 0.0
        sp = float(np.hypot(vx, vz))
        ax, az = r.px, r.pz
        bx, bz = (ax - vx * TAU_S, az - vz * TAU_S) if sp > 0 else (ax, az)
        dx, dz = bx - ax, bz - az
        L2 = dx * dx + dz * dz
        t = 0.0 if L2 == 0 else float(np.clip(((cx - ax) * dx + (cz - az) * dz) / L2, 0, 1))
        if np.hypot(cx - (ax + t * dx), cz - (az + t * dz)) <= HALF_WIDTH_M + margin:
            return True
    return False


def run_one(run: Path) -> list[dict]:
    session, cfg = S.session_of(run), S.load_config(run)
    margin = cfg["track"]["matchRadiusM"]
    _, trails, _ = G.analyze(run)
    if not trails:
        return []
    ev = S.E.load_events(session) or {}
    movers = {o["id"]: S.E.moving_track(o) for o in ev.get("objects", []) if o["kind"] == "moving" and o.get("added") != "check"}
    ob = pd.read_csv(run / "obstacles.csv")
    mo = pd.read_csv(run / "motion.csv")
    ins = pd.read_csv(run / "map_inserts.csv")
    first = ins.groupby(["ix", "iy", "iz"]).tCaptureNs.min()
    out = []
    for oid, tr in movers.items():
        pr = person_rows(mo, tr)
        if pr.empty:
            continue
        birth = pr.tCaptureNs.min()
        by_t = {t: g for t, g in pr.groupby("tCaptureNs")}
        tkeys = np.array(sorted(by_t))
        for tl in [x for x in trails if x["person"] == oid]:
            t_ns = int(ob.tCaptureNs.iloc[(ob.tCaptureNs - tl["t"] * 1e9).abs().argmin()])
            b = ob[(ob.tCaptureNs == t_ns) & (ob.id == tl["gate"])].iloc[0]
            cells = first[(first <= t_ns)]
            idx = cells.index.to_frame(index=False)
            cx, cy, cz = (idx.ix + 0.5) * VOX, (idx.iy + 0.5) * VOX, (idx.iz + 0.5) * VOX
            inb = ((cx >= b.aabbMinX) & (cx <= b.aabbMaxX) & (cy >= b.aabbMinY) & (cy <= b.aabbMaxY) & (cz >= b.aabbMinZ) & (cz <= b.aabbMaxZ)).values
            cnt = {"before_track": 0, "in_gate": 0, "out_gate": 0}
            for k in np.where(inb)[0]:
                tf = int(cells.values[k])
                if tf < birth:
                    cnt["before_track"] += 1
                    continue
                j = np.searchsorted(tkeys, tf, side="right") - 1  # 칸이 생긴 장 이전의 가장 가까운 사람 track 장(지난 장 창)
                rows = by_t[tkeys[j]] if j >= 0 and tf - tkeys[j] <= 0.1e9 else None
                cnt["in_gate" if rows is not None and in_gate(float(cx[k]), float(cz[k]), rows, margin) else "out_gate"] += 1
            out.append(dict(session=session.name[9:15], person=oid, t=round(tl["t"] - mo.tCaptureNs.min() / 1e9, 2), gate=tl["gate"],
                            cause=tl["cause"], birthS=round((birth - mo.tCaptureNs.min()) / 1e9, 2), **cnt))
    return out


def main(runs: list[Path]) -> None:
    rows = [r for run in runs for r in run_one(run)]
    if not rows:
        print("흔적 출력 없음")
        return
    d = pd.DataFrame(rows)
    d["n"] = d.before_track + d.in_gate + d.out_gate
    d["ownable"] = d.in_gate / d.n.clip(lower=1)
    print(d.to_string(index=False))
    print("흔적 출력", len(d), "| 칸 합: before_track", int(d.before_track.sum()), "in_gate", int(d.in_gate.sum()), "out_gate", int(d.out_gate.sum()))
    print("소유 규칙으로 모두 지워지는 출력(칸 전부 in_gate):", int((d.in_gate == d.n).sum()), "/", len(d),
          "| 과반 in_gate:", int((d.ownable > 0.5).sum()))


if __name__ == "__main__":
    main([Path(a) for a in sys.argv[1:]])
