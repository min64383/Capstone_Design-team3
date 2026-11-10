"""M57 2단계 두 길 판정의 값과 점검(재생 없음, derive.py와 같은 기록·창 정의, 창은 최근 W장까지).

    python judge2.py <재생 폴더>... [--out judge2.json] [--rate-per-h 1]

판정(사용자 승인 2026-11-08 "제안대로"): 문턱은 정지 분포에서만 정한다(움직이는 쪽 표본이 적을 때의 거짓 판정률 통제, Neyman–Pearson/CFAR).
- 보류: 창 비교 합 < N_min, 창 안 회전 > ω_max(derive.py 규칙, 회전은 그 위 모든 구간이 기준을 넘는 경계).
- q_ref = 나이 ≥ T_수렴이고 보류 아닌 정지 창 S의 99% 분위수(창 하나의 거짓 넘음 p = 1%).
- 라벨 길: 창 안 칸의 다수 라벨이 움직일 수 있는 라벨(사람·차량)이면 S > q_ref에서 움직임(나이 무관, 새 면 구간은 라벨이 가름).
- 기하 길: 나이 ≥ T_수렴에서 S > q_ref가 연속 K·W 원시 장이면 움직임. K = 요구(정지 물체 거짓 판정 ≤ rate/시간)에서:
  겹치지 않는 W장 묶음을 마르코프 사슬로 본다 — 정지 track 묶음이 넘을 확률 p1, 넘은 뒤 다음 묶음도 넘을 확률 c(자료에서 잼; 처음 판은
  독립 p^K였으나 130834 물체가 문턱 바로 위에 1 s 넘게 머물러 깨짐). K묶음이 모두 넘을 확률 p1·c^(K−1), 한 시간의 묶음 수
  B = (나이 ≥ T_수렴 정지 track 평균 수) × 3600 / (W·Δ). K = B·p1·c^(K−1) ≤ rate인 가장 작은 정수.
점검: 자료의 정지 track 거짓 판정(라벨 길·기하 길), 접근마다 첫 판정 대 마감, 사람 track이 기하 길로 판정되는 시각(보고).
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

import derive as D

MOVABLE = [9, 10]  # 차량, 사람(vision.movingLabels)
DT_S = 0.1


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+", type=Path)
    ap.add_argument("--out", type=Path)
    ap.add_argument("--rate-per-h", type=float, default=1.0)
    ap.add_argument("--vmin", type=float, default=0.171)
    ap.add_argument("--tconv", type=float, default=1.5)
    ap.add_argument("--nmin", type=float, default=50)
    ap.add_argument("--rotmax", type=float, default=100)
    a = ap.parse_args()
    D.PARTIAL = True
    e = D.load(a.runs, a.vmin)
    dl = D.deadlines(e)
    W = max(1, int(np.floor((dl.deadline - dl.t0).min() / DT_S)))
    w = D.windows(e, W)
    w["major"] = [int(np.argmax(l)) if l.sum() > 0 else -1 for l in w.labs]
    w["labelMove"] = w.major.isin(MOVABLE)
    held = (w.n < a.nmin) | (w.rot > a.rotmax)
    st = w[(w.kind == "static") & ~held]
    q_ref = float(np.quantile(st[st.age >= a.tconv].S, 0.99))
    p = 0.01
    # 정지 track 평균 수: 정지 행(나이 ≥ T_수렴)을 원시 장마다 센 평균
    rows = e[(e.kind == "static") & (e.ageS >= a.tconv)]
    per_frame = rows.groupby(["session", "tRawNs"]).size()
    frames = e.groupby("session").tRawNs.nunique().sum()
    n_static = float(per_frame.sum() / frames)
    B = n_static * 3600 / (W * DT_S)
    old = st[st.age >= a.tconv].sort_values(["session", "id", "tRawNs"])
    nb = ex = first = cont = 0
    for _, x in old.groupby(["session", "id"]):
        b = x.S.values[::W] > q_ref  # 겹치지 않는 묶음
        nb += len(b)
        ex += int(b.sum())
        for u, v in zip(b[:-1], b[1:]):
            if u:
                first += 1
                cont += int(v)
    p1, c = ex / max(nb, 1), cont / max(first, 1)
    K = next(k for k in range(1, 50) if B * p1 * c ** (k - 1) <= a.rate_per_h)
    run_need = K * W
    print(f"W {W}, q_ref {q_ref:.3f} (나이 ≥ {a.tconv} s 정지 창 {int((st.age >= a.tconv).sum())}), 정지 track 평균 {n_static:.2f}개/원시 장, "
          f"시간당 묶음 {B:.0f}, 묶음 넘음 p1 {p1:.4f}({ex}/{nb}), 이어 넘음 c {c:.2f}({cont}/{first}) → K {K} (연속 {run_need}장 = {run_need * DT_S:.1f} s), "
          f"기대 거짓 판정 {B * p1 * c ** (K - 1):.3f}/시간; 자료 {e.groupby('session').tRawNs.nunique().sum() * DT_S / 60:.1f} 분")
    # 판정 시뮬레이션: track마다 시간순
    w = w.sort_values(["session", "id", "tRawNs"]).reset_index(drop=True)
    w["held"] = held.values if False else ((w.n < a.nmin) | (w.rot > a.rotmax))
    w["exceed"] = (w.S > q_ref) & ~w.held
    w["run"] = 0
    for _, idx in w.groupby(["session", "id"]).groups.items():
        r = 0
        for i in idx:
            r = r + 1 if (w.at[i, "exceed"] and w.at[i, "age"] >= a.tconv) else 0
            w.at[i, "run"] = r
    w["byLabel"] = w.labelMove & w.exceed
    w["byGeom"] = w.run >= run_need
    w["judged"] = w.byLabel | w.byGeom
    sf = w[(w.kind == "static") & w.judged]
    print(f"정지 track 거짓 판정: 라벨 길 {sf[sf.byLabel].groupby(['session', 'id']).ngroups} track, 기하 길 {sf[sf.byGeom].groupby(['session', 'id']).ngroups} track")
    if len(sf):
        print(sf.groupby(["session", "id"]).agg(t=("tRawNs", "min"), S=("S", "max"), major=("major", "first"), age=("age", "min"),
                                                lab=("byLabel", "any"), geo=("byGeom", "any")).round(3).to_string())
    res = []
    for _, d in dl.iterrows():
        x = w[(w.session == d.session) & (w.person == d.person) & (w.kind == "person")]
        lab = x[x.byLabel].tRawNs.min() / 1e9 - d.t0 if x.byLabel.any() else None
        geo = x[x.byGeom].tRawNs.min() / 1e9 - d.t0 if x.byGeom.any() else None
        res.append((d.session[9:15], d.person, None if lab is None else round(lab, 2), None if geo is None else round(geo, 2), round(d.deadline - d.t0, 2)))
    print("접근(라벨 길 판정, 기하 길 판정, 마감) s:", res)
    st_all = w[w.kind == "static"]
    print(f"보류 몫: 정지 창 {st_all.held.mean():.3f}, 사람 창 {w[w.kind == 'person'].held.mean():.3f}")
    if a.out:
        a.out.write_text(json.dumps(dict(W=W, qRef=round(q_ref, 4), K=K, p1=round(p1, 4), c=round(c, 3), runFrames=run_need, staticTracks=round(n_static, 3), blocksPerH=round(B),
                                         tConvS=a.tconv, nMin=a.nmin, rotMaxDegS=a.rotmax, vMin=a.vmin, ratePerH=a.rate_per_h), indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
