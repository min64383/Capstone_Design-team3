"""M57 A-0 보정 변형 시험(재생 없음): stage0 viol을 (지난 장 화소 신뢰도 하한, v_min)만 바꿔 다시 재고, judge_l과 같은 판정 모의로 비교.

    python a0_variants.py <out_dir> <past_conf> <v_min> [<past_conf> <v_min> ...]

보고: 정지 창(W 5) S > 0.3·≥ 0.8 수, A, 다섯 접근의 첫 L > A − 마감 시작(마감 예산과 함께), 정지 창을 라벨 없음 사전(0.5)으로 계산한 L의 최댓값.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

import free_space
import judge_l as J
import stage0 as S

ROOT = Path(__file__).resolve().parents[3]
RUNS = sorted((ROOT / "data/sweeps/m55/in_BW5").iterdir()) + sorted((ROOT / "data/sweeps/m55/out_BW5/o01").iterdir()) \
    + sorted((ROOT / "data/sweeps/m55/out_BW5/t").iterdir())
TABLE = ROOT / "data/sweeps/m56/stage0/sigma.json"


def judge(path: Path) -> dict:
    """judge_l.run과 같은 계산을 값으로 돌려준다."""
    d = pd.read_csv(path)
    d = d[d.kind.isin(["static", "person"])].copy()
    tr = d.groupby(["session", "kind", "gate"])[J.LABS].sum()
    tr = tr[tr.sum(axis=1) > 0]
    maj = tr.values.argmax(1)
    mov = tr.index.get_level_values("kind") == "person"
    p_l = np.full(12, 0.5)
    for l in np.unique(maj):
        k, n = mov[maj == l].sum(), (maj == l).sum()
        p_l[l] = (k + 1) / (n + 2)
    dl = []
    for (s, g), x in d[d.kind == "person"].sort_values("tRawNs").groupby(["session", "gate"]):
        x = x[(x.nCmp >= 5) & (x.hd <= J.DA_MAX_M)]
        if x.empty:
            continue
        t = x.tRawNs.values / 1e9
        later = x[t >= t[0] + 0.5]
        if later.empty:
            continue
        v = (x.hd.values[0] - later.hd.values[0]) / (later.tRawNs.values[0] / 1e9 - t[0])
        if v <= J.V_MIN:
            continue
        dl.append(dict(session=s, gate=g, t0=t[0], deadline=t[0] + (x.hd.values[0] - v * J.TTC_S) / v - J.TAU_S))
    dl = pd.DataFrame(dl)
    W = max(1, int(np.floor((dl.deadline - dl.t0).min() / J.DT_S)))
    rows = []
    for (s, k, g), x in d.sort_values("tRawNs").groupby(["session", "kind", "gate"]):
        sw = free_space.window_stat(x.nCmp.values, x.nViol.values, W, 5)
        cum = np.cumsum(np.vstack([np.zeros(12), x[J.LABS].values.astype(float)]), axis=0)
        for i in range(len(x)):
            if np.isfinite(sw[i]):
                share = cum[i + 1] - cum[max(0, i + 1 - W)]
                p = float(share @ p_l / share.sum()) if share.sum() > 0 else 0.5
                rows.append(dict(session=s, kind=k, gate=g, t=x.tRawNs.values[i] / 1e9, S=sw[i], p=p,
                                 speed=x.speed.values[i] if "speed" in x else np.nan))
    r = pd.DataFrame(rows)
    edges = np.linspace(0, 1, J.NB + 1)
    st, mv = r[r.kind == "static"], r[(r.kind == "person") & (r.speed >= J.V_MIN)]
    f0 = (np.histogram(st.S, edges)[0] + 1) / (len(st) + J.NB)
    f1 = (np.histogram(mv.S, edges)[0] + 1) / (len(mv) + J.NB)
    llr = np.log(f1 / f0)
    b = np.clip(np.digitize(r.S, edges) - 1, 0, J.NB - 1)
    r["L"] = np.log(r.p / (1 - r.p)) + llr[b]
    r["L0"] = llr[b]  # 라벨 없음 사전 0.5
    A = r[r.kind == "static"].L.max()
    out = dict(W=W, A=round(float(A), 2), llr=llr.round(2).tolist(), staticS03=int((st.S > 0.3).sum()), staticS08=int((st.S >= 0.8).sum()),
               static=int(len(st)), L0maxStatic=round(float(r[r.kind == "static"].L0.max()), 2), approaches=[])
    for _, a in dl.iterrows():
        x = r[(r.session == a.session) & (r.gate == a.gate) & (r.kind == "person")]
        hit = x[x.L > A]
        out["approaches"].append((a.session[9:15], a.gate, round(float(hit.t.min() - a.t0), 2) if len(hit) else None, round(float(a.deadline - a.t0), 2)))
    return out


def main() -> None:
    out_dir = Path(sys.argv[1])
    args = sys.argv[2:]
    orig = S.compare
    for pc, vm in zip(args[::2], args[1::2]):
        pc, vm = int(pc), float(vm)
        S.compare = lambda p, past, _mc, _pc=pc: orig(p, past, _pc)
        path = out_dir / f"viol_pc{pc}_v{vm:.2f}.csv"
        if not path.is_file():
            S.viol(RUNS, path, TABLE, vm, 0.1)
        print(f"past conf ≥ {pc}, v_min {vm}:", judge(path), flush=True)
    S.compare = orig


if __name__ == "__main__":
    main()
