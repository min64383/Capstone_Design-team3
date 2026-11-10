"""M56 A-3 갈림 원인(재생 없음): 정답 사람이 카메라 3 m 안인 장 중 사람 자리(정답 0.8 m 안)에 출력이 둘 이상인 장을 나눈다.

    python split_cause.py <재생 폴더>...

(가) 정지 출력끼리, (나) 움직이는 track끼리(이번 장 칸 군집이 갈려 후보가 따로 생겨 움직이는 track이 됨), (다) 움직이는 track + 정지 출력
(뒤처진 평활·흔적, M57 몫). 움직이는 track = 그 장 motion.csv에서 moving인 id. (가)는 두 출력 상자 사이 수평 틈도 적는다.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "motion_judge"))
import stage0 as S  # noqa: E402
from gap_trail import PERSON_ZONE_M, box_dist  # noqa: E402

MULTI_M = 3.0


def box_gap(a, b) -> float:
    dx = max(a.aabbMinX - b.aabbMaxX, b.aabbMinX - a.aabbMaxX, 0.0)
    dz = max(a.aabbMinZ - b.aabbMaxZ, b.aabbMinZ - a.aabbMaxZ, 0.0)
    return float(np.hypot(dx, dz))


def analyze(run: Path) -> list[dict]:
    session = S.session_of(run)
    fr = S.load_frames(session)
    ev = S.E.load_events(session) or {}
    movers = [(o["id"], S.E.moving_track(o)) for o in ev.get("objects", []) if o["kind"] == "moving" and o.get("added") != "check"]
    ob = pd.read_csv(run / "obstacles.csv")
    mo = pd.read_csv(run / "motion.csv")
    cam = fr.drop_duplicates("depthTNs").set_index("depthTNs")[["tx", "tz"]]
    times = np.sort(mo.tCaptureNs.unique()) if len(mo) else np.sort(ob.tCaptureNs.unique())
    obg, mog = dict(tuple(ob.groupby("tCaptureNs"))), dict(tuple(mo.groupby("tCaptureNs")))
    rows = []
    for oid, tr in movers:
        for t_ns in times:
            q = tr(t_ns / 1e9)
            if q is None or t_ns not in cam.index:
                continue
            pos, c = q[0], cam.loc[t_ns]
            if np.hypot(pos[0] - c.tx, pos[1] - c.tz) > MULTI_M:
                continue
            o_now = obg.get(t_ns)
            near = [] if o_now is None else [b for b in o_now.itertuples() if box_dist(pos[0], pos[1], b) <= PERSON_ZONE_M]
            if len(near) < 2:
                continue
            m_now = mog.get(t_ns)
            moving = set() if m_now is None else set(m_now[m_now.moving].id)
            nm = sum(b.id in moving for b in near)
            ns = len(near) - nm
            kind = "가 정지끼리" if nm == 0 else ("나 움직이는 track끼리" if ns == 0 else "다 움직이는 track + 정지")
            sta = [b for b in near if b.id not in moving]
            gap = min((box_gap(a, b) for i, a in enumerate(sta) for b in sta[i + 1:]), default=np.nan)
            rows.append(dict(session=session.name[9:15], person=oid, t=t_ns, kind=kind, nMoving=nm, nStatic=ns, staticGapM=gap))
    return rows


def main(runs: list[Path]) -> None:
    r = pd.DataFrame([x for run in runs for x in analyze(run)])
    if r.empty:
        print("출력 둘 이상인 장 없음")
        return
    print(r.groupby(["session", "kind"]).size().unstack(fill_value=0).to_string())
    g = r[r.kind.str.startswith("가")].staticGapM
    if len(g):
        print("(가) 정지 출력 사이 수평 틈 m:", np.round(np.sort(g.to_numpy()), 3).tolist())


if __name__ == "__main__":
    main([Path(a) for a in sys.argv[1:]])
