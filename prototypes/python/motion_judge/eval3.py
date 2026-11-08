"""M57 3단계 판정 점검(설계 표 9 중 판정 몫, 재생 없음): 정지 물체 거짓 판정과 다가오는 사람 판정 시각.

    python eval3.py <변형 재생 폴더(in·t·o01 아래 세션들)>...

정지 거짓 판정: motion.csv에서 움직이는 track이 된 track 중, 움직이는 정답이 없는 세션(실내·O01)의 모든 것과, T에서 움직이는 정답(정답이 있는
시각)의 1.0 m 밖에 있던 것(칼만 위치, 없으면 측정). 정답이 없는 시각의 T 움직이는 track은 모름으로 따로 센다.
다가오는 사람: 마감은 derive.py와 같은 정의(그 재생의 motion_evidence.csv), 판정 = 움직이는 track 위치가 정답 사람 0.8 m 안인 첫 시각.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

import derive as D
import stage0 as S


def main(runs: list[Path]) -> None:
    false, unknown = [], []
    late = []
    for run in runs:
        if not (run / "motion.csv").is_file():
            continue
        session = S.session_of(run)
        mo = pd.read_csv(run / "motion.csv")
        mv = S.movers(session)
        m = mo[mo.moving].copy()
        m["px"] = m.posX.where(np.isfinite(m.posX), m.measX)
        m["pz"] = m.posZ.where(np.isfinite(m.posZ), m.measZ)
        for tid, g in m.groupby("id"):
            kinds = set()
            for r in g.itertuples():
                if not mv:
                    kinds.add("false")
                    continue
                qs = [tr(r.tCaptureNs / 1e9) for _, tr, _, _ in mv]
                qs = [q for q in qs if q is not None]
                if not qs:
                    kinds.add("unknown")
                elif np.isfinite(r.px) and min(np.hypot(r.px - q[0][0], r.pz - q[0][1]) for q in qs) > 1.0:
                    kinds.add("false")
                else:
                    kinds.add("person")
            if "false" in kinds:
                false.append((session.name[9:15], int(tid), round((g.tCaptureNs.min() - mo.tCaptureNs.min()) / 1e9, 2)))
            elif kinds == {"unknown"}:
                unknown.append((session.name[9:15], int(tid)))
        if mv and (run / "motion_evidence.csv").is_file():
            e = D.load([run], 0.171)
            for _, d in D.deadlines(e).iterrows():
                tr = next(t for oid, t, _, _ in mv if oid == d.person)
                hit = [r.tCaptureNs / 1e9 for r in m.itertuples() if (q := tr(r.tCaptureNs / 1e9)) is not None and np.isfinite(r.px)
                       and np.hypot(r.px - q[0][0], r.pz - q[0][1]) <= 0.8]
                t1 = min(hit) - d.t0 if hit else None
                late.append((session.name[9:15], d.person, None if t1 is None else round(t1, 2), round(d.deadline - d.t0, 2)))
    print(f"정지 거짓 판정 track {len(false)}: {false}")
    print(f"정답 없는 시각만의 움직이는 track {len(unknown)}: {unknown}")
    print(f"다가오는 사람(판정, 마감) s: {late}")


if __name__ == "__main__":
    main([Path(a) for a in sys.argv[1:]])
