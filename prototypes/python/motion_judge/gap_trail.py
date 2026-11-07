"""M56 0-4 판정 전 빈틈·0-7 흔적(설계 표 5, 재생 없음): 기존 재생 출력(obstacles·motion·cluster_debug)과 사람 정답으로 센다.

    python gap_trail.py <재생 폴더>...

0-4: 정답 사람이 카메라 2 m 안인 깊이 장 중 사람 자리(정답 0.8 m 안)에 출력이 없는 장을 상태로 나눈다.
     후보 점유 전(살아 있는 후보, 표 < map.minHits) / 후보 점유(표 ≥ minHits, 판정 전이라 출력 안 됨) / 지도 군집만(출력 안 됨) /
     후보가 지워짐(지금 아무것도 없고 0.5 s 안에 그 자리 후보가 있었음) / 아무것도 없음.
0-7: 출력(움직이는 track 제외) 중 정답 경로에서 사람이 떠나고 τ가 지난 자리(그 자리 0.8 m 안)에 있고, 지금 사람에서 0.8 m 밖이며,
     정답 정지 물체 0.8 m 밖이고, 사람이 오기 전(그 자리를 지나기 0.5 s 전까지)에는 그 자리에 출력이 없던 것 = 흔적.
     원인: (1) 평활 뒤처짐 = 가장 가까운 지나간 시각이 지금 − τ − 0.2 s 안, (2) 판정 전 쌓임 = 그 사람이 움직이는 track으로 판정되기 전에
     지나간 자리, 그 밖은 (3) 그 밖. 가려짐 = 지금 카메라에서 보아 그 자리가 사람 몸(반지름 radiusM) 뒤에 있음(지도가 깎지 못함).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

import stage0 as S

PERSON_ZONE_M = 0.8
NEAR_M = 2.0
TAU_S = 0.345  # M52 평활 뒤처짐
LOST_WINDOW_S = 0.5
BEFORE_S = 0.5


def box_dist(x: float, z: float, b) -> float:
    dx = max(b.aabbMinX - x, 0.0, x - b.aabbMaxX)
    dz = max(b.aabbMinZ - z, 0.0, z - b.aabbMaxZ)
    return float(np.hypot(dx, dz))


def analyze(run: Path) -> tuple[dict, list]:
    session, cfg = S.session_of(run), S.load_config(run)
    min_hits = cfg["map"]["minHits"]
    fr = S.load_frames(session)
    ev = S.E.load_events(session) or {}
    movers = [(o["id"], S.E.moving_track(o), float(o.get("radiusM", 0.2))) for o in ev.get("objects", [])
              if o["kind"] == "moving" and o.get("added") != "check"]
    statics = [np.array([c["pointW"][0], c["pointW"][2]]) for o in ev.get("objects", []) if o["kind"] == "static"
               for c in S.E.clicks_of(o)]
    ob = pd.read_csv(run / "obstacles.csv")
    mo = pd.read_csv(run / "motion.csv")
    cl = pd.read_csv(run / "cluster_debug.csv")
    moving_ids = set(mo[mo.moving].id)
    cam = fr.drop_duplicates("depthTNs").set_index("depthTNs")[["tx", "tz"]]
    times = np.sort(mo.tCaptureNs.unique()) if len(mo) else np.sort(ob.tCaptureNs.unique())
    gap = {}
    trails = []
    for oid, tr, rad in movers:
        # 그 사람이 움직이는 track으로 처음 판정된 시각(움직이는 track 위치가 정답 0.8 m 안)
        dec = None
        for r in mo[mo.moving].sort_values("tCaptureNs").itertuples():
            q = tr(r.tCaptureNs / 1e9)
            if q is not None and np.isfinite(r.posX) and np.hypot(r.posX - q[0][0], r.posZ - q[0][1]) <= PERSON_ZONE_M:
                dec = r.tCaptureNs / 1e9
                break
        hist = []  # 지나간 (시각, x, z)
        for t_ns in times:
            t = t_ns / 1e9
            q = tr(t)
            if q is None or t_ns not in cam.index:
                continue
            pos = q[0]
            hist.append((t, pos[0], pos[1]))
            c = cam.loc[t_ns]
            hd = float(np.hypot(pos[0] - c.tx, pos[1] - c.tz))
            o_now = ob[ob.tCaptureNs == t_ns]
            m_now = mo[mo.tCaptureNs == t_ns]
            # 0-4
            if hd <= NEAR_M:
                hit = any(box_dist(pos[0], pos[1], b) <= PERSON_ZONE_M for b in o_now.itertuples())
                if hit:
                    state = "output"
                else:
                    cands = [r for r in m_now.itertuples() if r.candidate and np.isfinite(r.posX if np.isfinite(r.posX) else r.measX)
                             and np.hypot((r.posX if np.isfinite(r.posX) else r.measX) - pos[0], (r.posZ if np.isfinite(r.posZ) else r.measZ) - pos[1]) <= PERSON_ZONE_M]
                    clus = [b for b in cl[cl.tCaptureNs == t_ns].itertuples() if box_dist(pos[0], pos[1], b) <= PERSON_ZONE_M]
                    if cands:
                        state = "cand_occupied" if max(r.votes for r in cands) >= min_hits else "cand_before_occupied"
                    elif clus:
                        state = "map_cluster_only"
                    else:
                        recent = mo[(mo.tCaptureNs < t_ns) & (mo.tCaptureNs >= t_ns - LOST_WINDOW_S * 1e9) & mo.candidate]
                        lost = any(np.isfinite(r.measX) and np.hypot(r.measX - pos[0], r.measZ - pos[1]) <= PERSON_ZONE_M + 0.5 for r in recent.itertuples())
                        state = "cand_removed" if lost else "nothing"
                gap[state] = gap.get(state, 0) + 1
            # 0-7: 흔적 출력
            for b in o_now.itertuples():
                if b.id in moving_ids:
                    continue
                if box_dist(pos[0], pos[1], b) <= PERSON_ZONE_M:
                    continue
                if any(box_dist(sp[0], sp[1], b) <= PERSON_ZONE_M for sp in statics):
                    continue
                past = [(s, x, z) for s, x, z in hist if s <= t - TAU_S and box_dist(x, z, b) <= PERSON_ZONE_M]
                if not past:
                    continue
                s_last = max(s for s, _, _ in past)  # 가장 늦게 그 자리를 지난 시각
                s_first = min(s for s, _, _ in past)
                before = ob[(ob.tCaptureNs < (s_first - BEFORE_S) * 1e9)]
                if any(box_dist((b.aabbMinX + b.aabbMaxX) / 2, (b.aabbMinZ + b.aabbMaxZ) / 2, o) <= PERSON_ZONE_M * 0.5 for o in before.itertuples()):
                    continue  # 사람이 오기 전부터 있던 출력
                cause = "lag" if s_last >= t - TAU_S - 0.2 else ("pre_decision" if dec is None or s_last < dec else "other")
                # 가려짐: 그 자리(상자 중심)가 사람보다 멀고, 카메라에서 본 방위 차가 사람 몸의 반각 안
                bx, bz = (b.aabbMinX + b.aabbMaxX) / 2, (b.aabbMinZ + b.aabbMaxZ) / 2
                db = float(np.hypot(bx - c.tx, bz - c.tz))
                ang = abs(np.arctan2(bx - c.tx, bz - c.tz) - np.arctan2(pos[0] - c.tx, pos[1] - c.tz))
                ang = min(ang, 2 * np.pi - ang)
                occl = db > hd and ang <= np.arctan2(rad, hd)
                trails.append(dict(session=session.name, person=oid, t=t, gate=b.id, cause=cause, occluded=bool(occl),
                                   afterDecision=dec is not None and t >= dec, ageS=t - s_last))
    return gap, trails


def main(runs: list[Path]) -> None:
    allgap, alltr = {}, []
    for run in runs:
        gap, tr = analyze(run)
        n = sum(gap.values())
        print(f"{run.name}: 2 m 안 장 {n}, " + ", ".join(f"{k} {v} ({v / n:.0%})" for k, v in sorted(gap.items())) if n else f"{run.name}: 2 m 안 장 없음")
        for k, v in gap.items():
            allgap[k] = allgap.get(k, 0) + v
        alltr += tr
    n = sum(allgap.values())
    print("합계 2 m 안 장", n, {k: f"{v} ({v / n:.0%})" for k, v in sorted(allgap.items())})
    t = pd.DataFrame(alltr)
    if t.empty:
        print("흔적 출력 없음")
        return
    print("흔적 출력(장 × 출력):", len(t), "세션별", t.session.value_counts().to_dict())
    print("  원인", t.cause.value_counts().to_dict(), "가려짐", int(t.occluded.sum()), "판정 뒤", int(t.afterDecision.sum()))
    print("  흔적이 남은 장 수(사람·장 단위):", t.groupby(["session", "t"]).size().shape[0], " 떠난 뒤 나이 s p50/p90",
          np.percentile(t.ageS, [50, 90]).round(2).tolist())


if __name__ == "__main__":
    main([Path(a) for a in sys.argv[1:]])
