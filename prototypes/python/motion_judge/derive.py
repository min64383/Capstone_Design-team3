"""M57 2단계 판정 값 유도(설계 표 4·표 8 A-3·A-4, 재생 없음): 1단계 지도 기록 재생의 motion_evidence.csv(track × 새 원시 장)로.

    python derive.py <재생 폴더>... [--out derived.json] [--vmin 0.171]

창: track 하나의 최근 W 원시 장(그 track 행 W개)의 위반 합 ÷ 비교 합 = S(비교 합 < 5면 없음).
사람 창: 창 상자가 정답 사람 0.8 m 안(track 종류 무관), 정답 속도 ≥ v_min이면 움직이는 사람 창. 정지 창: 움직이는 정답이 없는 세션의 모든 창,
T는 움직이는 정답에서 1.0 m 밖 창(M56 0단계와 같은 정의).
값(표 4): W = 가장 빠듯한 접근 마감 안의 원시 장 수. 사전 p_라벨 = track 다수 칸 라벨별 움직인 track 몫 Beta(1,1), 없는 라벨 0.5.
ln LR = 정지·움직이는 사람 창 S 히스토그램(10구간, +1) 비를 구간 창 수 가중 단조 회귀(S에 대해 줄지 않음).
T_수렴 = 정지 창 S 99% 분위수가 나이 2 s 넘은 창 값(q_ref) 이하로 내려와 그 뒤 구간도 모두 그 아래인 가장 짧은 나이(0.5 s 단위).
N_min = 나이 ≥ T_수렴 정지 창을 창 비교 합 구간으로 나눠 그 구간부터 위로 모든 구간의 S 99% ≤ q_ref인 가장 작은 구간 아래 끝.
ω_max = 같은 창을 창 안 최대 회전 속도 구간으로 나눠 그 구간까지 아래로 모든 구간의 S 99% ≤ q_ref인 가장 큰 구간 위 끝.
판정 보류 = 창 비교 합 < N_min, 창 안 회전 > ω_max, 라벨 정보 없는 창(사전을 잰 라벨의 칸 없음)은 나이 < T_수렴.
정지 창 = 창 안 행이 모두 정지(정답 있는 시각, 움직이는 정답 상자 1.0 m 밖), 사람 창 = 마지막 행이 움직이는 사람(창 상자 0.8 m 안).
A = 보류하지 않은 정지 창의 L(실제 사전) 최댓값과, 그중 나이 ≥ T_수렴 창의 L(사전 0.5) 최댓값 중 큰 쪽.
점검: A-3 움직이는 사람 track의 움직이는 칸 중 사람 라벨 몫, A-4 접근마다 첫 L > A(보류 아닌 사람 창) 대 마감, 사전 95% 구간 끝 민감도.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

import stage0 as S

LAB = [f"lab{i}" for i in range(12)]
OWN = [f"own{i}" for i in range(12)]
PERSON = 10
TAU_S, TTC_S, DT_S, DA_MAX_M, NB = 0.15, 1.0, 0.1, 2.75, 10
AGE_EDGES = np.arange(0.0, 3.01, 0.5)
N_EDGES = [5, 25, 50, 100, 200, 400, 10 ** 9]
ROT_EDGES = [0, 15, 30, 60, 100, 200, 10 ** 9]


def load(runs: list[Path], v_min: float) -> pd.DataFrame:
    out = []
    for run in runs:
        f = run / "motion_evidence.csv"
        if not f.is_file():
            continue
        e = pd.read_csv(f)
        session = S.session_of(run)
        fr = S.load_frames(session)
        cam_t, cam_x, cam_z = fr.tNs.to_numpy(), fr.tx.to_numpy(), fr.tz.to_numpy()
        mv = S.movers(session)
        k = np.clip(np.searchsorted(cam_t, e.tNs.to_numpy(), side="right") - 1, 0, len(cam_t) - 1)
        e["camX"], e["camZ"] = cam_x[k], cam_z[k]
        e["session"], e["group"] = session.name, S.group_of(run)
        e["kind"], e["person"], e["speed"], e["hd"] = "static", "", np.nan, np.nan
        for i, r in enumerate(e.itertuples(index=False)):
            near = None
            covered = False
            for oid, tr, _, _ in mv:
                q = tr(r.tRawNs / 1e9)
                if q is None:
                    continue
                covered = True
                # 창 상자까지 수평 거리(gap_trail 사람 자리와 같은 정의)
                d = float(np.hypot(max(r.loX - q[0][0], 0.0, q[0][0] - r.hiX), max(r.loZ - q[0][1], 0.0, q[0][1] - r.hiZ)))
                if d <= 1.0 and (near is None or d < near[1]):
                    near = (oid, d, q)
            if near is None:
                if mv and not covered:  # 움직이는 정답이 있는 세션에서 정답이 없는 시각(클릭 밖)은 정지인지 모른다
                    e.iat[i, e.columns.get_loc("kind")] = "unknown"
                continue
            oid, d, q = near
            spd = float(np.hypot(*q[1])) if np.all(np.isfinite(q[1])) else np.nan
            if d <= 0.8 and np.isfinite(spd) and spd >= v_min:
                e.iat[i, e.columns.get_loc("kind")] = "person"
            else:
                e.iat[i, e.columns.get_loc("kind")] = "near"
            e.iat[i, e.columns.get_loc("person")] = oid
            e.iat[i, e.columns.get_loc("speed")] = spd
            e.iat[i, e.columns.get_loc("hd")] = float(np.hypot(q[0][0] - r.camX, q[0][1] - r.camZ))
        out.append(e)
    return pd.concat(out, ignore_index=True)


PARTIAL = False  # True면 track이 막 생겨 W장이 안 될 때도 있는 장으로 창을 낸다(최근 W장까지)
ROT_TAIL = False  # True면 ω_max = 그 위로 모든 회전 구간의 S 99%가 q_ref를 넘는 가장 낮은 구간 아래 끝(N_min과 같은 꼴의 규칙)


def windows(e: pd.DataFrame, W: int) -> pd.DataFrame:
    rows = []
    for (s, tid), x in e.sort_values("tRawNs").groupby(["session", "id"]):
        cn, cv = np.cumsum(np.r_[0, x.nCmp.values]), np.cumsum(np.r_[0, x.nViol.values])
        cl = np.cumsum(np.vstack([np.zeros(12), x[LAB].values]), axis=0)
        rot = pd.Series(x.rotDegS.abs().values).rolling(W, min_periods=1).max().values
        for i in range(len(x)):
            j = max(0, i + 1 - W)
            if i + 1 < W and not PARTIAL:
                continue
            n = cn[i + 1] - cn[j]
            if n < 5:
                continue
            r = x.iloc[i]
            ks = set(x.kind.values[j:i + 1])
            # 정지 창 = 창 안 행이 모두 정지, 사람 창 = 마지막 행이 사람(앞 행은 사람·가까움), 그 밖(섞임·모름)은 뺀다
            kind = "static" if ks == {"static"} else ("person" if r.kind == "person" and ks <= {"person", "near"} else "other")
            rows.append(dict(session=s, id=tid, tRawNs=r.tRawNs, kind=kind, person=r.person, speed=r.speed, hd=r.hd, group=r.group,
                             S=(cv[i + 1] - cv[j]) / n, n=n, rot=rot[i], age=r.ageS, moving=r.moving, labs=cl[i + 1] - cl[j]))
    return pd.DataFrame(rows)


def deadlines(e: pd.DataFrame) -> pd.DataFrame:
    out = []
    for (s, oid), x in e[e.kind == "person"].sort_values("tRawNs").groupby(["session", "person"]):
        x = x[(x.nCmp >= 5) & (x.hd <= DA_MAX_M)]
        if x.empty:
            continue
        t = x.tRawNs.values / 1e9
        later = x[t >= t[0] + 0.5]
        if later.empty:
            continue
        v = (x.hd.values[0] - later.hd.values[0]) / (later.tRawNs.values[0] / 1e9 - t[0])
        if v <= 0.17:
            continue
        out.append(dict(session=s, person=oid, t0=t[0], d0=x.hd.values[0], v=v, deadline=t[0] + (x.hd.values[0] - v * TTC_S) / v - TAU_S))
    return pd.DataFrame(out)


def q99(x: pd.Series) -> float:
    return float(np.quantile(x, 0.99)) if len(x) else float("nan")


def pava(y: np.ndarray, w: np.ndarray) -> np.ndarray:
    """가중 단조(줄지 않음) 회귀: 이웃 위반 구간을 가중 평균으로 합친다."""
    blocks = [[float(y[i]), float(w[i]), 1] for i in range(len(y))]
    i = 0
    while i < len(blocks) - 1:
        if blocks[i][0] > blocks[i + 1][0]:
            a, b = blocks[i], blocks[i + 1]
            blocks[i] = [(a[0] * a[1] + b[0] * b[1]) / (a[1] + b[1]), a[1] + b[1], a[2] + b[2]]
            del blocks[i + 1]
            i = max(0, i - 1)
        else:
            i += 1
    return np.concatenate([[v] * n for v, _, n in blocks])


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+", type=Path)
    ap.add_argument("--out", type=Path)
    ap.add_argument("--vmin", type=float, default=0.171)
    ap.add_argument("--partial", action="store_true")
    ap.add_argument("--rot-tail", action="store_true")
    a = ap.parse_args()
    global PARTIAL, ROT_TAIL
    PARTIAL, ROT_TAIL = a.partial, a.rot_tail
    e = load(a.runs, a.vmin)
    dl = deadlines(e)
    budget = (dl.deadline - dl.t0).min()
    W = max(1, int(np.floor(budget / DT_S)))
    print("approaches:\n", dl.assign(budget=dl.deadline - dl.t0).round(3).to_string(index=False))
    print(f"W = {W} (가장 빠듯한 예산 {budget:.2f} s)")
    w = windows(e, W)
    # A-3
    mp = e[(e.kind == "person") & e.moving]
    own = mp[OWN].sum()
    print(f"A-3 움직이는 사람 track의 움직이는 칸 라벨: 사람 몫 {own.iloc[PERSON] / max(own.sum(), 1):.3f} (칸 {int(own.sum())}), 라벨별 {own.to_dict()}")
    # 사전
    tr = e[e.kind.isin(["static", "person"])].groupby(["session", "id"]).agg({**{c: "sum" for c in LAB}, "kind": lambda k: (k == "person").any()})
    tr = tr[tr[LAB].sum(axis=1) > 0]
    maj = tr[LAB].values.argmax(1)
    mov = tr.kind.values.astype(bool)
    p_l, lo_l, hi_l = np.full(12, 0.5), np.full(12, 0.5), np.full(12, 0.5)
    from judge_l import _beta_q
    for l in np.unique(maj):
        k, n = int(mov[maj == l].sum()), int((maj == l).sum())
        p_l[l] = (k + 1) / (n + 2)
        lo_l[l], hi_l[l] = _beta_q(k + 1, n - k + 1, 0.025), _beta_q(k + 1, n - k + 1, 0.975)
        print(f"  라벨 {l}: track {n}, 움직인 {k} → p {p_l[l]:.3f} [{lo_l[l]:.3f}, {hi_l[l]:.3f}]")

    def prior(labs, pl):
        s = labs.sum()
        return float(labs @ pl / s) if s > 0 else 0.5
    w["p"] = [prior(l, p_l) for l in w.labs]
    est = np.array([l in set(np.unique(maj)) for l in range(12)])
    w["labelFree"] = [float(l[est].sum()) == 0 for l in w.labs]  # 사전을 잰 라벨의 칸이 창 안에 없음
    st, mvw = w[w.kind == "static"], w[w.kind == "person"]
    # T_수렴
    q_ref = q99(st[st.age >= 2.0].S)
    ages = [(lo, q99(st[(st.age >= lo) & (st.age < lo + 0.5)].S)) for lo in AGE_EDGES[:-1]]
    T_conv = next((lo for i, (lo, _) in enumerate(ages) if all(not (q > q_ref) for _, q in ages[i:])), 2.0)
    print(f"정지 창 S 99%: 나이 ≥ 2 s {q_ref:.3f}; 나이 구간 {[(lo, round(q, 3)) for lo, q in ages]} → T_수렴 {T_conv}")
    old = st[st.age >= T_conv]
    nb = [(N_EDGES[i], N_EDGES[i + 1], q99(old[(old.n >= N_EDGES[i]) & (old.n < N_EDGES[i + 1])].S), int(((old.n >= N_EDGES[i]) & (old.n < N_EDGES[i + 1])).sum())) for i in range(len(N_EDGES) - 1)]
    N_min = next((lo for i, (lo, _, _, _) in enumerate(nb) if all(not (q > q_ref) for _, _, q, _ in nb[i:])), N_EDGES[-2])
    rb = [(ROT_EDGES[i], ROT_EDGES[i + 1], q99(old[(old.rot >= ROT_EDGES[i]) & (old.rot < ROT_EDGES[i + 1])].S), int(((old.rot >= ROT_EDGES[i]) & (old.rot < ROT_EDGES[i + 1])).sum())) for i in range(len(ROT_EDGES) - 1)]
    W_max = ROT_EDGES[0]
    for lo, hi, q, _ in rb:
        if q > q_ref:
            break
        W_max = hi
    if ROT_TAIL:
        W_max = next((rb[i][0] for i in range(len(rb)) if all(q > q_ref or not np.isfinite(q) for _, _, q, _ in rb[i:])), ROT_EDGES[-1])
    print(f"비교 합 구간(아래, 위, S 99%, 창 수) {[(l, h, round(q, 3), c) for l, h, q, c in nb]} → N_min {N_min}")
    print(f"회전 구간 {[(l, h, round(q, 3), c) for l, h, q, c in rb]} → ω_max {W_max}")
    # 판정 보류: 비교 점 적음, 빠른 회전, 라벨 정보 없는 창은 나이 T_수렴 전(새 면 수렴 전에는 깊이로 못 가름, A-0)
    held = (w.n < N_min) | (w.rot > W_max) | (w.labelFree & (w.age < T_conv))
    # ln LR
    edges = np.linspace(0, 1, NB + 1)
    b0 = np.histogram(st[~held.loc[st.index]].S, edges)[0]
    b1 = np.histogram(mvw[~held.loc[mvw.index]].S, edges)[0]
    f0, f1 = (b0 + 1) / (b0.sum() + NB), (b1 + 1) / (b1.sum() + NB)
    raw_llr = np.log(f1 / f0)
    llr = pava(raw_llr, b0 + b1 + 2)
    print("ln LR 원래", raw_llr.round(2).tolist(), "\n     단조", llr.round(2).tolist())
    bi = np.clip(np.digitize(w.S, edges) - 1, 0, NB - 1)
    w["L"] = np.log(w.p / (1 - w.p)) + llr[bi]
    w["L0"] = llr[bi]
    stv = w[(w.kind == "static") & ~held]
    A_lab, A_free = stv.L.max(), stv[stv.age >= T_conv].L0.max()
    A = float(max(A_lab, A_free))
    top = stv.assign(Lmax=np.maximum(stv.L, np.where(stv.age >= T_conv, stv.L0, -np.inf))).sort_values("Lmax", ascending=False).head(4)
    print(f"A = {A:.2f} (실제 사전 최댓값 {A_lab:.2f}, 나이 ≥ T_수렴 라벨 없음 최댓값 {A_free:.2f}); 문턱을 정한 창:\n",
          top[["session", "id", "S", "p", "n", "rot", "age", "L", "L0"]].round(3).to_string(index=False))
    res = []
    for _, d in dl.iterrows():
        x = w[(w.session == d.session) & (w.person == d.person) & (w.kind == "person") & ~held]
        hit = x[x.L > A]
        res.append((d.session[9:15], d.person, round(hit.tRawNs.min() / 1e9 - d.t0, 2) if len(hit) else None, round(d.deadline - d.t0, 2)))
    print("A-4 (판정 − 첫 증거, 마감) s:", res)
    # 민감도: 사람 아래 끝, 나머지 위 끝
    p_s = np.where(np.arange(12) == PERSON, lo_l, hi_l)
    w["Ls"] = np.log(np.array([prior(l, p_s) for l in w.labs]) / (1 - np.array([prior(l, p_s) for l in w.labs]))) + llr[bi]
    A_s = max(w[(w.kind == "static") & ~held].Ls.max(), A_free)
    res_s = []
    for _, d in dl.iterrows():
        x = w[(w.session == d.session) & (w.person == d.person) & (w.kind == "person") & ~held]
        hit = x[x.Ls > A_s]
        res_s.append((d.session[9:15], d.person, round(hit.tRawNs.min() / 1e9 - d.t0, 2) if len(hit) else None, round(d.deadline - d.t0, 2)))
    print(f"민감도(사전 95% 끝) A {A_s:.2f}:", res_s)
    print(f"보류 몫: 정지 창 {held[w.kind == 'static'].mean():.3f}, 사람 창 {held[w.kind == 'person'].mean():.3f}")
    if a.out:
        a.out.write_text(json.dumps(dict(W=W, priors=p_l.round(4).tolist(), llr=llr.round(4).tolist(), A=round(A, 4), tConvS=float(T_conv),
                                         nMin=int(N_min), rotMaxDegS=float(W_max), vMin=a.vmin), indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
