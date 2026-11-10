"""0-2·0-3 판정 모의(설계 3.3절·표 4): L = logit(p) + ln(f1(S_W)/f0(S_W)), p = 창 안 원시 점 라벨 몫 × 라벨별 사전.

    python judge_l.py <viol.csv>

사전: 정답 track(창)을 다수 라벨로 나눠 움직인 몫, Beta(1, 1) 평활, 정답 track 없는 라벨은 0.5.
f0·f1: 정지 창·움직이는 사람 창(정답 속도 ≥ v_min)의 S_W 히스토그램(10구간, 구간마다 +1 평활).
마감: 사람 창이 원시 D_a(2.75 m) 안에서 처음 증거를 낸 때의 거리 D0와 그 뒤 0.5 s 동안 줄어든 거리로 잰 상대 접근 속도 v로 ((D0 − v·1.0)/v − 0.15) s.
W = 가장 빠른 접근의 마감 안에 들어가는 가장 큰 장 수(원시 0.1 s). A = 정답 정지 창 L의 최댓값.
"""
from __future__ import annotations

import sys

import numpy as np
import pandas as pd

from free_space import window_stat

V_MIN, TAU_S, STOP_M, TTC_S, DT_S, NB, DA_MAX_M = 0.17, 0.15, 1.0, 1.0, 0.1, 10, 2.75
LABS = [f"lab{i}" for i in range(12)]
PESSIMISTIC = False


def _beta_q(a: float, b: float, q: float) -> float:
    """베타 분포 분위수(정수 a, b): 누적 = 이항 꼬리 합으로 계산해 이분법."""
    from math import comb
    n = int(a + b - 1)

    def cdf(x):  # I_x(a, b) = P(Binom(n, x) ≥ a)
        return sum(comb(n, j) * x ** j * (1 - x) ** (n - j) for j in range(int(a), n + 1))
    lo, hi = 0.0, 1.0
    for _ in range(60):
        mid = (lo + hi) / 2
        lo, hi = (mid, hi) if cdf(mid) < q else (lo, mid)
    return (lo + hi) / 2


def run(path: str) -> None:
    d = pd.read_csv(path)
    d = d[d.kind.isin(["static", "person"])].copy()
    if "nCmp" not in d:
        d["nCmp"], d["nViol"] = d.nCmpHi + d.nCmpLo, d.nViolHi + d.nViolLo
    tr = d.groupby(["session", "kind", "gate"])[LABS].sum()
    tr = tr[tr.sum(axis=1) > 0]
    maj = tr.values.argmax(1)
    mov = (tr.index.get_level_values("kind") == "person")
    p_l = np.full(12, 0.5)
    for l in np.unique(maj):
        k, n = mov[maj == l].sum(), (maj == l).sum()
        p_l[l] = (k + 1) / (n + 2)
        if PESSIMISTIC:  # 민감도(설계 표 4): Beta(1 + k, 1 + n − k) 95% 구간 끝 — 움직인 track이 많은 라벨은 아래 끝, 없는 라벨은 위 끝
            from statistics import NormalDist  # noqa: F401 (scipy 없이: 베타 분위수는 아래 이분법)
            p_l[l] = _beta_q(k + 1, n - k + 1, 0.025 if k > (n - k) else 0.975)
    # 마감
    dl = []
    for (s, g), x in d[d.kind == "person"].sort_values("tRawNs").groupby(["session", "gate"]):
        x = x[(x.nCmp >= 5) & (x.hd <= DA_MAX_M)]  # 원시 깊이에 사람이 나타나는 거리(M52 D_a 원시 2.19~2.75 m) 안에서 처음 증거를 낸 때
        if x.empty:
            continue
        t = x.tRawNs.values / 1e9
        t0, d0 = t[0], x.hd.values[0]
        later = x[(t >= t0 + 0.5)]
        if later.empty:
            continue
        v = (d0 - later.hd.values[0]) / (later.tRawNs.values[0] / 1e9 - t0)
        if v <= V_MIN:
            continue
        dl.append(dict(session=s, gate=g, t0=t0, d0=d0, v=v, deadline=t0 + (d0 - v * TTC_S) / v - TAU_S))
    dl = pd.DataFrame(dl)
    print("approaches:\n", dl.assign(budget=dl.deadline - dl.t0).round(3).to_string(index=False))
    budget = (dl.deadline - dl.t0).min()
    W = max(1, int(np.floor(budget / DT_S)))
    print(f"W = {W} (fastest budget {budget:.2f} s); priors {dict(zip(range(12), p_l.round(3)))}")
    rows = []
    for (s, k, g), x in d.sort_values("tRawNs").groupby(["session", "kind", "gate"]):
        sw = window_stat(x.nCmp.values, x.nViol.values, W, 5)
        lc = x[LABS].values.astype(float)
        cum = np.cumsum(np.vstack([np.zeros(12), lc]), axis=0)
        for i in range(len(x)):
            if not np.isfinite(sw[i]):
                continue
            share = cum[i + 1] - cum[max(0, i + 1 - W)]
            p = float(share @ p_l / share.sum()) if share.sum() > 0 else 0.5
            rows.append(dict(session=s, kind=k, gate=g, t=x.tRawNs.values[i] / 1e9, S=sw[i], p=p,
                             speed=x.speed.values[i] if "speed" in x else np.nan, hd=x.hd.values[i] if "hd" in x else np.nan))
    r = pd.DataFrame(rows)
    edges = np.linspace(0, 1, NB + 1)
    st, mv = r[r.kind == "static"], r[(r.kind == "person") & (r.speed >= V_MIN)]
    f0 = (np.histogram(st.S, edges)[0] + 1) / (len(st) + NB)
    f1 = (np.histogram(mv.S, edges)[0] + 1) / (len(mv) + NB)
    llr = np.log(f1 / f0)
    b = np.clip(np.digitize(r.S, edges) - 1, 0, NB - 1)
    r["L"] = np.log(r.p / (1 - r.p)) + llr[b]
    print("ln LR by S bin:", llr.round(2).tolist())
    A = r[r.kind == "static"].L.max()
    top = r[r.kind == "static"].sort_values("L", ascending=False).head(5)
    print(f"A = {A:.2f} (static max L); top static windows:\n", top[["session", "gate", "t", "S", "p", "L"]].round(3).to_string(index=False))
    for _, a in dl.iterrows():
        x = r[(r.session == a.session) & (r.gate == a.gate) & (r.kind == "person")]
        hit = x[x.L > A]
        first = hit.t.min() if len(hit) else np.nan
        print(f"  {a.session} {a.gate}: first L > A at {first - a.t0 if np.isfinite(first) else float('nan'):+.2f} s, deadline {a.deadline - a.t0:+.2f} s,"
              f" L max {x.L.max():.2f}, p median {x.p.median():.2f}")


if __name__ == "__main__":
    PESSIMISTIC = len(sys.argv) > 2 and sys.argv[2] == "pessimistic"
    run(sys.argv[1])
