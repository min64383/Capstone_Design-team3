"""M51 순환 확인(명세 §13 M51 v0.4.48, 설계 표 7) 중 기록으로 바로 세는 행: 값은 T에서 정한 그대로 두고 맡은 조건이 지켜지는지 본다.
    python eval_circ.py <C 재생 폴더>...      (track.freeSpaceRecord 켠 재생: motion_evidence.csv, foot.csv)
- 위반 여유: 정지 행(움직이는 정답 1 m 밖, derive.load와 같은 정의)의 원시 점 위반 몫 Σ위반/Σ비교, 거리 구간별. 목표 ≤ 2.3%.
- 위반 문턱 0.112: 보류 아닌(비교 ≥ 50, 회전 ≤ 100°/s) 나이 ≥ 1.5 s 정지 창 중 S > 0.112 몫. 목표 1%.
- 수렴·비교 점·회전: 같은 창을 나이·비교 합·회전 구간으로 나눠 구간마다 S 99% 값 ≤ 0.112인지.
- 발 접점: 측정 − 정답 앞면(eval_m53.errors와 같은 짝짓기) ÷ 그 측정의 모형 σ(foot.csv sigmaM), |z| ≤ 2 몫. 목표 95%.
비율은 Clopper–Pearson 95% 구간. 깨짐 = 아래 끝 > 목표(위 둘), 위 끝 < 95%(발 접점). 표본(점·창)은 서로 독립이 아니어서 구간은 낙관적이다.
"""
from __future__ import annotations

import sys
from pathlib import Path

import math

import numpy as np
import pandas as pd


def _betacf(a: float, b: float, x: float) -> float:
    """정규화 불완전 베타의 연분수(Numerical Recipes betacf)."""
    qab, qap, qam = a + b, a + 1, a - 1
    c, d = 1.0, 1.0 - qab * x / qap
    d = 1.0 / (d if abs(d) > 1e-300 else 1e-300)
    h = d
    for m in range(1, 300):
        m2 = 2 * m
        for aa in (m * (b - m) * x / ((qam + m2) * (a + m2)), -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))):
            d = 1.0 + aa * d
            d = 1.0 / (d if abs(d) > 1e-300 else 1e-300)
            c = 1.0 + aa / c if abs(1.0 + aa / c) > 1e-300 else 1e-300
            h *= d * c
        if abs(d * c - 1.0) < 1e-12:
            break
    return h


def betainc(a: float, b: float, x: float) -> float:
    if x <= 0:
        return 0.0
    if x >= 1:
        return 1.0
    lb = math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b) + a * math.log(x) + b * math.log1p(-x)
    if x < (a + 1) / (a + b + 2):
        return math.exp(lb) * _betacf(a, b, x) / a
    return 1.0 - math.exp(lb) * _betacf(b, a, 1 - x) / b


class beta:  # scipy 없이 Clopper–Pearson에 필요한 분위수만(이분법)
    @staticmethod
    def ppf(q: float, a: float, b: float) -> float:
        lo, hi = 0.0, 1.0
        for _ in range(100):
            mid = (lo + hi) / 2
            lo, hi = (mid, hi) if betainc(a, b, mid) < q else (lo, mid)
        return (lo + hi) / 2

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "prototypes/python/motion_judge"))
import derive as D  # noqa: E402
import stage0 as S  # noqa: E402

Q_REF, T_CONV, N_MIN, ROT_MAX, W = 0.112, 1.5, 50, 100.0, 5
Z_EDGES = [0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0]


def cp(k: int, n: int) -> tuple[float, float]:
    if n == 0:
        return float("nan"), float("nan")
    lo = 0.0 if k == 0 else float(beta.ppf(0.025, k, n - k + 1))
    hi = 1.0 if k == n else float(beta.ppf(0.975, k + 1, n - k))
    return lo, hi


def fmt(k: int, n: int) -> str:
    lo, hi = cp(k, n)
    return f"{k}/{n} = {k / n:.2%} [{lo:.2%}, {hi:.2%}]" if n else "표본 없음"


def main(runs: list[Path]) -> None:
    e = D.load(runs, 0.171)
    st = e[e.kind == "static"]
    print(f"== 위반 여유(정지 행 {len(st)}, 원시 점 단위)")
    k, n = int(st.nViol.sum()), int(st.nCmp.sum())
    print(f"  전체 {fmt(k, n)}  → {'깨짐' if cp(k, n)[0] > 0.023 else '깨짐 아님'}(목표 2.3%)")
    st = st.assign(zb=pd.cut(st.zMedM, Z_EDGES))
    for b, g in st.groupby("zb", observed=True):
        k, n = int(g.nViol.sum()), int(g.nCmp.sum())
        print(f"  {b}: {fmt(k, n)}{'  깨짐' if n and cp(k, n)[0] > 0.023 else ''}")

    D.PARTIAL = True
    w = D.windows(e, W)
    held = (w.n < N_MIN) | (w.rot > ROT_MAX)
    sw = w[(w.kind == "static") & ~held & (w.age >= T_CONV)]
    k, n = int((sw.S > Q_REF).sum()), len(sw)
    print(f"== 위반 문턱 {Q_REF}: 정지 창 넘음 {fmt(k, n)}  → {'깨짐' if cp(k, n)[0] > 0.01 else '깨짐 아님'}(목표 1%)")
    sw = sw.assign(major=[int(np.argmax(l)) if l.sum() > 0 else -1 for l in sw.labs])
    for (s, maj), g in sw.groupby(["session", "major"]):
        print(f"  {s[9:15]} 다수 칸 라벨 {maj}: 창 {len(g)}, 넘음 {(g.S > Q_REF).sum()}, S 99% {np.quantile(g.S, .99):.3f}, track {sorted(g.id.unique())[:6]}")
    print("== 수렴·비교 점·회전 구간(보류 아닌 정지 창, 구간마다 S 99% 값; 기준 ≤ 0.112)")
    base = w[w.kind == "static"]
    for name, col, edges, keep in (("나이 s", "age", [0, 0.5, 1.0, 1.5, 2.0, 2.5, 99], (base.n >= N_MIN) & (base.rot <= ROT_MAX)),
                                   ("비교 합", "n", [5, 25, 50, 100, 200, 400, 1e9], (base.age >= T_CONV) & (base.rot <= ROT_MAX)),
                                   ("회전 °/s", "rot", [0, 15, 30, 60, 100, 200, 1e9], (base.age >= T_CONV) & (base.n >= N_MIN))):
        g = base[keep].assign(b=pd.cut(base[keep][col], edges, right=False))
        cells = [f"{b}: {np.quantile(x.S, .99):.3f}(n{len(x)})" for b, x in g.groupby("b", observed=True)]
        print(f"  {name}: " + ", ".join(cells))

    print("== 발 접점 |z| ≤ 2")
    zs = []
    for run in runs:
        if not (run / "foot.csv").is_file():
            continue
        session = S.session_of(run)
        mv = S.movers(session)
        foot = pd.read_csv(run / "foot.csv")
        fr = pd.read_csv(session / "frames.csv").drop_duplicates("tNs").sort_values("tNs")
        for r in foot[foot.kind == "M"].itertuples():
            for _, tr, _, _ in mv:
                q = tr(r.tNs / 1e9)
                if q is None or np.hypot(r.measX - q[0][0], r.measZ - q[0][1]) > 0.6:
                    continue
                x, z = np.interp(r.tNs, fr.tNs, fr.tx), np.interp(r.tNs, fr.tNs, fr.tz)
                dt = float(np.hypot(q[0][0] - x, q[0][1] - z)) - 0.15
                zs.append(dict(session=session.name[9:15], dt=dt, z=(r.distM - dt) / r.sigmaM, sigma=r.sigmaM))
    if zs:
        d = pd.DataFrame(zs)
        k, n = int((d.z.abs() <= 2).sum()), len(d)
        print(f"  전체 {fmt(k, n)}  → {'깨짐' if cp(k, n)[1] < 0.95 else '깨짐 아님'}(목표 95%), z 중앙값 {d.z.median():+.2f}, 모형 σ 중앙값 {d.sigma.median():.3f} m")
        d["bin"] = pd.cut(d.dt, [1.5, 2.5, 3.5, 4.5, 8.0])
        for b, g in d.groupby("bin", observed=True):
            print(f"  {b}: |z|≤2 {fmt(int((g.z.abs() <= 2).sum()), len(g))}, z 중앙값 {g.z.median():+.2f}")
    else:
        print("  발 접점 측정 없음")


if __name__ == "__main__":
    main([Path(a) for a in sys.argv[1:]])
