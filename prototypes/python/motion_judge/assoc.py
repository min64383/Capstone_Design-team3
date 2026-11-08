"""M59 설계 측정: 움직이는 track의 원시 측정 혁신(재생 없음, 기존 재생의 motion.csv + 클릭 정답).

    python assoc.py <재생 폴더(T)>...

받아들인 원시 측정(measTNs가 바뀐 줄)마다 혁신 = 측정 − 지난 원시 측정 뒤 칼만 상태를 이번 원시 시각까지 예측한 점(수평).
지난 상태는 그 줄의 posW(촬영 시각 예측)를 속도로 원시 시각까지 되돌린 것이다. 예측 분산 ≈ σ_p² + Δt²σ_v² + q Δt⁴/4
(교차 공분산 pv를 빼 조금 작게 잡음, 근사). 정답 짝 = 측정과 예측이 모두 정답 사람 중심 0.5 m 안, 다른 대상 = 측정이 정답 밖이거나
다른 정답에 가까움. R_사람(한 축) ≈ E[d²]/2 − E[예측 분산](혁신으로 측정 잡음 추정, Mehra 1970).
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

import stage0 as S

NEAR_M = 0.5
CLEAN_N = 10  # 원시 약 10 Hz에서 1 s
Q = 1.13 ** 2  # track.accelSigmaMps2²
S0, S2 = 0.032, 0.0024  # track.measSigma0M, measSigmaPerM2


def rows(run: Path):
    session = S.session_of(run)
    mv = S.movers(session)
    fr = S.load_frames(session)
    cam_t = fr.tNs.to_numpy()
    cams = fr[["tx", "tz"]].to_numpy()
    m = pd.read_csv(run / "motion.csv")
    m = m[m.moving.astype(bool) & m.measX.notna()]
    out = []
    for tid, g in m.groupby("id"):
        g = g.drop_duplicates("measTNs").sort_values("measTNs")
        prev = None
        for r in g.itertuples(index=False):
            if prev is not None and r.measTNs > prev.measTNs:
                back = (prev.tCaptureNs - prev.measTNs) / 1e9
                p0 = np.array([prev.posX - prev.velX * back, prev.posZ - prev.velZ * back])
                dt = (r.measTNs - prev.measTNs) / 1e9
                pred = p0 + np.array([prev.velX, prev.velZ]) * dt
                meas = np.array([r.measX, r.measZ])
                var_p = prev.posSigmaM ** 2 + dt * dt * prev.velSigmaMps ** 2 + Q * dt ** 4 / 4
                gt = [(oid, a(r.measTNs / 1e9)) for oid, a, _, _ in mv]
                gt = [(oid, np.asarray(q[0])) for oid, q in gt if q is not None]
                dmo = sorted((float(np.linalg.norm(meas - c)), oid) for oid, c in gt) or [(np.inf, None)]
                dp = min((np.linalg.norm(pred - c) for _, c in gt), default=np.inf)
                cam = cams[np.searchsorted(cam_t, r.measTNs).clip(0, len(cam_t) - 1)]
                z = float(np.hypot(*(meas - cam)))
                sm = S0 + S2 * z * z
                d = float(np.linalg.norm(meas - pred))
                out.append(dict(run=run.name, id=tid, t=r.measTNs, dt=dt, d=d, var_p=var_p, n=r.nRawPoints, dm=dmo[0][0],
                                gt=dmo[0][1] if dmo[0][0] <= NEAR_M else None, dp=dp, z=z, nis=d * d / (var_p + sm * sm)))
            prev = r
    df = pd.DataFrame(out, columns=["run", "id", "t", "dt", "d", "var_p", "n", "dm", "gt", "dp", "z", "nis"]).infer_objects()
    # 깨끗한 짝: 이 측정과 이 track의 지난 CLEAN_N 측정이 모두 같은 정답 사람 0.5 m 안(정답이 바뀐 뒤 칼만이 다시 맞는 동안을 뺌)
    df["clean"] = False
    for _, g in df.groupby("id"):
        ids = g["gt"].tolist()
        for k, i in enumerate(g.index):
            w = ids[max(0, k - CLEAN_N):k + 1]
            df.loc[i, "clean"] = ids[k] is not None and all(x == ids[k] for x in w)
    return df


def main() -> None:
    runs = [Path(a) for a in sys.argv[1:]]
    df = pd.concat([rows(r) for r in runs], ignore_index=True)
    df[["dt", "d", "var_p", "dm", "dp", "z", "nis"]] = df[["dt", "d", "var_p", "dm", "dp", "z", "nis"]].astype(float)
    ok = df[df.clean]
    print(f"받아들인 원시 측정 {len(df)}, 깨끗한 정답 짝 {len(ok)}")
    for g in (9.21, 13.8):
        print(f"정적 R(σ_m(Z))로 NIS > {g}: 깨끗한 짝 {(ok.nis > g).sum()}/{len(ok)}, 전체 {(df.nis > g).sum()}/{len(df)}")
    print("깨끗한 짝 NIS 분위", [round(ok.nis.quantile(x), 2) for x in (.5, .9, .99, 1.0)], "평균", round(ok.nis.mean(), 2), "(χ²₂ 평균 2)")
    print("Δt s: 중앙", round(df.dt.median(), 3), "p95", round(df.dt.quantile(.95), 3), "최대", round(df.dt.max(), 3))
    q = [.5, .9, .95, .99, 1.0]
    print("정답 짝 혁신 d(m) 분위", [round(ok.d.quantile(x), 3) for x in q])
    print("정답 짝 예측 σ_p(m) 중앙", round(np.sqrt(ok.var_p).median(), 3))
    r_axis = (ok.d ** 2).mean() / 2 - ok.var_p.mean()
    print("R_사람 한 축 ≈", round(r_axis, 5), "σ =", round(np.sqrt(max(r_axis, 0)), 3), "m")
    by = ok.groupby("run").d.agg(["size", "median", lambda s: s.quantile(.99), "max"])
    print(by.round(3).to_string())
    print("NIS 큰 측정(> 9.21):")
    print(df[df.nis > 9.21][["run", "id", "t", "d", "n", "gt", "dm", "z", "nis", "clean"]].round(3).to_string())


if __name__ == "__main__":
    main()
