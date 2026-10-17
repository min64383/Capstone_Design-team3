"""픽셀 깊이 오차 (M33 설계 3.5절). 깊이 영상의 화소마다 e = 측정 깊이 − 정답 깊이(카메라 깊이 Z, + = 실제보다 멀게)를 구해
카메라 깊이·입사각·정답 깊이 경계까지의 화소 거리로 나누고, 편향 b = 중앙값, σ = 1.4826 × MAD를 낸다.

    python pixel_error.py <세션 폴더> ... --runs <정렬 폴더(<세션ID>/align.json, M32 align_ref)> --out <출력 폴더>
        → pixel_error.json(구간 표, b(Z) = b₀ + b₁·Z 맞춤, 입사각 계수 c, 교차 검사), 출력

- 정답 깊이: 정렬한 정답 상자(정답 밖 물체 `unmodeled` 빼고)와 바닥에 화소 광선을 쏜 첫 교차(core `SceneEval.raycast`와 같은 계산).
  바닥은 같은 깊이 원천의 1~2 m 바닥(`align.json` floorRef.rawM·smoothedM)을 기준으로 한 상대 값이다: 세 바닥 값이 세션마다 ±0.1 m 이상
  달라(M32 표 3) 정답 바닥으로는 먼 바닥 화소의 오차를 가를 수 없다.
- 판정 불가 화소: 벽·천장·물체는 면 법선 방향 정답 불확실성 u의 광선 방향 값 u / cos θ가 칸 크기(0.05 m)를 넘으면 뺀다. u = 0.03(표시 오차)
  + 정렬 각 오차로 그 점이 법선 방향으로 움직이는 양. 각 오차는 시작 표시 둘레 회전이라 점 (x, z)를 (−z, x)·tan(각) 만큼 옮기므로 x 법선 면은
  tan·|z|, z 법선 면은 tan·|x|, 수평 면은 0이다(정면 끝 벽은 각 오차에 둔감하다).
- 면 상수: 세션·면마다 카메라 깊이 1~2 m 화소의 e 중앙값(줄자·정렬 상수, 세션 전체에 같은 값)을 빼고 잰다(M30 오차 모델과 같다). 복셀
  지도에서 관측끼리 어긋나게 하는 것은 같은 세션 안에서 거리·입사각에 따라 달라지는 오차이고, 세션 공통 상수는 지도 전체를 옮길 뿐이다.
  그래서 b(Z)는 1~2 m 대비 상대 편향이다. 1~2 m 화소가 없는 면은 뺀다.
- 경계 거리: 정답 깊이 영상에서 이웃 화소와 깊이가 10% 넘게 다르거나 다른 면인 화소가 경계(0), 거기서 4방향 화소 거리(4 이상은 4).
- 맞춤(경계 4 화소 이상): b(Z)는 입사각 65° 미만 구간 중앙값의 표본 수 가중 직선, 입사각 계수 c는 σ(Z, θ)² = σ(Z)² + (c·Z·tan θ / f_x)²를
  구간 σ로 최소제곱(σ(Z)는 앱 설정 M31 계수). 교차 검사는 세션을 이름 순 번갈아 둘로 나눠 한쪽 맞춤으로 다른 쪽 |e − b| ≤ σ 비율.
  구간 경계는 결과를 보기 전에 정한 자체 선택이다(설계 표 5).
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

from align import DEFAULT_CONFIG, load_frames, to_truth
from metrics import load_truth
from spike_check import quat_to_rot
from worldpts import DEPTH_COLUMN, depth_k, depth_rows, load_depth_m

VOXEL_M = 0.05
MARK_M = 0.03
Z_EDGES = np.arange(0.5, 5.01, 0.5)
THETA_EDGES = [0.0, 45.0, 65.0, 80.0, 90.0]
EDGE_MAX_PX = 4
EDGE_REL = 0.10
SMOOTHED_EVERY = 3
SOURCES = ("RAW", "SMOOTHED")
REF_Z_M = (1.0, 2.0)


def raycast(o: np.ndarray, d: np.ndarray, boxes: list[tuple[np.ndarray, np.ndarray, str]], floor_y: float):
    """광선 o + t·d(정답 좌표, d는 (N, 3))의 첫 교차 t, 면 법선 축(0·1·2), 면 번호(0 = 바닥, i+1 = 상자 i), 요소 이름."""
    n = len(d)
    t = np.full(n, np.inf)
    axis = np.full(n, 1)
    face = np.zeros(n, dtype=int)
    with np.errstate(divide="ignore", invalid="ignore"):
        tf = (floor_y - o[1]) / d[:, 1]
    ok = (d[:, 1] < 0) & (tf > 1e-4)
    t[ok] = tf[ok]
    for i, (lo, hi, _) in enumerate(boxes):
        with np.errstate(divide="ignore", invalid="ignore"):
            t1 = (lo - o) / d
            t2 = (hi - o) / d
        tmin = np.minimum(t1, t2)
        tmax = np.maximum(t1, t2)
        tmin = np.where(np.isnan(tmin), -np.inf, tmin)
        tmax = np.where(np.isnan(tmax), np.inf, tmax)
        enter = tmin.max(axis=1)
        leave = tmax.min(axis=1)
        hit = (enter <= leave) & (enter > 1e-4) & (enter < t)
        t[hit] = enter[hit]
        axis[hit] = tmin[hit].argmax(axis=1)
        face[hit] = i + 1
    return t, axis, face


def edge_distance(zt: np.ndarray, face: np.ndarray) -> np.ndarray:
    """경계 화소(이웃과 깊이 10% 넘게 다르거나 다른 면)에서의 4방향 화소 거리, 상한 EDGE_MAX_PX."""
    e = np.zeros(zt.shape, dtype=bool)
    for dy, dx in ((0, 1), (1, 0)):
        a, b = zt[: zt.shape[0] - dy, : zt.shape[1] - dx], zt[dy:, dx:]
        fa, fb = face[: zt.shape[0] - dy, : zt.shape[1] - dx], face[dy:, dx:]
        m = (np.abs(a - b) > EDGE_REL * np.minimum(a, b)) | (fa != fb)
        e[: zt.shape[0] - dy, : zt.shape[1] - dx] |= m
        e[dy:, dx:] |= m
    dist = np.full(zt.shape, EDGE_MAX_PX)
    cur = e
    for k in range(EDGE_MAX_PX):
        dist[cur & (dist == EDGE_MAX_PX)] = k
        nxt = cur.copy()
        nxt[1:] |= cur[:-1]
        nxt[:-1] |= cur[1:]
        nxt[:, 1:] |= cur[:, :-1]
        nxt[:, :-1] |= cur[:, 1:]
        cur = nxt
    return dist


def session_pixels(session: Path, al_file: Path, source: str) -> pd.DataFrame:
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    al = json.loads(al_file.read_text(encoding="utf-8"))
    cfg = json.loads(DEFAULT_CONFIG.read_text(encoding="utf-8"))
    truth = [o for o in (load_truth(session, cfg)[0] or []) if o.get("kind") != "unmodeled"]
    ref = al.get("floorRef", {}).get("rawM" if source == "RAW" else "smoothedM")
    floor_y = (ref - al["floorY"]) if ref is not None else 0.0
    boxes = [(np.array(o["min"], float), np.array(o["max"], float), "object" if o["kind"] == "object" else "structure") for o in truth]
    rot_only = {**al, "origin": [0.0, 0.0], "floorY": 0.0}
    tan_al = np.tan(np.radians(al.get("angleUncertaintyDeg", 0.0)))
    frames = load_frames(session)
    tcol = "depthTNs" if source == "SMOOTHED" else "rawDepthTNs"
    rows = depth_rows(frames, source).drop_duplicates(tcol)
    if source == "SMOOTHED":
        rows = rows.iloc[::SMOOTHED_EVERY]
    out = []
    k = None
    for _, row in rows.iterrows():
        d = load_depth_m(session / row[DEPTH_COLUMN[source]])
        if k is None or (k["width"], k["height"]) != (d.shape[1], d.shape[0]):
            k = depth_k(meta, d.shape[1], d.shape[0])
            v, u = np.mgrid[0:d.shape[0], 0:d.shape[1]]
            dir_gl = np.stack([(u.ravel() - k["cx"]) / k["fx"], -(v.ravel() - k["cy"]) / k["fy"], -np.ones(u.size)], axis=1)
        r = quat_to_rot(np.array([[row.qx, row.qy, row.qz, row.qw]]))[0]
        dw = dir_gl @ r.T
        dt = np.stack(to_truth(rot_only, dw[:, 0], dw[:, 1], dw[:, 2]), axis=1)
        o = np.array(to_truth(al, row.tx, row.ty, row.tz), dtype=float)
        t, axis, face = raycast(o, dt, boxes, floor_y)
        zt = t.reshape(d.shape)
        ed = edge_distance(np.where(np.isfinite(zt), zt, 1e6), face.reshape(d.shape)).ravel()
        z = d.ravel()
        ok = (z > 0) & np.isfinite(t)
        hit = o + dt * t[:, None]
        cos = np.abs(dt[np.arange(len(dt)), axis]) / np.linalg.norm(dt, axis=1)
        el = np.where(face == 0, "floor", np.array(["floor"] + [b[2] for b in boxes])[face])
        shift = np.where(axis == 0, np.abs(hit[:, 2]), np.where(axis == 2, np.abs(hit[:, 0]), 0.0)) * tan_al
        u_al = np.where(face == 0, 0.0, MARK_M + shift) / np.maximum(cos, 1e-3)
        ok &= u_al <= VOXEL_M
        out.append(pd.DataFrame({"session": session.name, "Z": z[ok], "e": z[ok] - t[ok], "thetaDeg": np.degrees(np.arccos(np.clip(cos[ok], 0, 1))),
                                 "edgePx": ed[ok], "element": el[ok], "face": face[ok]}))
    if not out:
        return pd.DataFrame()
    df = pd.concat(out, ignore_index=True)
    ref = df[(df.Z >= REF_Z_M[0]) & (df.Z < REF_Z_M[1]) & (df.edgePx >= EDGE_MAX_PX)].groupby("face").e.median()
    df["offset"] = df.face.map(ref)
    df = df[df.offset.notna()].copy()
    df["e"] = df.e - df.offset
    return df


def robust(e: np.ndarray) -> tuple[float, float]:
    b = float(np.median(e))
    return b, float(1.4826 * np.median(np.abs(e - b)))


def bins(df: pd.DataFrame) -> pd.DataFrame:
    df = df.assign(zb=pd.cut(df.Z, Z_EDGES), tb=pd.cut(df.thetaDeg, THETA_EDGES, include_lowest=True),
                   eb=pd.cut(df.edgePx, [-0.5, 1.5, 3.5, EDGE_MAX_PX + 0.5], labels=["0-1", "2-3", "4+"]))
    rows = []
    for (zb, tb, eb), g in df.groupby(["zb", "tb", "eb"], observed=True):
        if len(g) < 50:
            continue
        b, s = robust(g.e.to_numpy())
        rows.append({"zMid": zb.mid, "thetaLo": tb.left, "thetaHi": tb.right, "edge": eb, "n": len(g), "b": b, "sigma": s})
    return pd.DataFrame(rows)


def sigma_z(cfg_depth: dict, raw: bool, z):
    return np.maximum(0.0, (cfg_depth["rawSigma0M"] + cfg_depth["rawSigmaPerM2"] * z * z) if raw else
                      (cfg_depth["smoothedSigma0M"] + cfg_depth["smoothedSigmaPerM2"] * z * z))


def fit(st: pd.DataFrame, raw: bool, fx: float, cfg_depth: dict) -> dict:
    far = st[st.edge == "4+"]
    lo = far[far.thetaHi <= 65]
    w = lo.n.to_numpy(float)
    a = np.stack([np.ones(len(lo)), lo.zMid.to_numpy(float)], axis=1)
    b0, b1 = np.linalg.lstsq(a * np.sqrt(w)[:, None], lo.b.to_numpy(float) * np.sqrt(w), rcond=None)[0] if len(lo) >= 2 else (0.0, 0.0)
    th = np.radians((far.thetaLo.to_numpy(float) + far.thetaHi.to_numpy(float)) / 2)
    x = (far.zMid.to_numpy(float) * np.tan(th) / fx) ** 2
    y = far.sigma.to_numpy(float) ** 2 - sigma_z(cfg_depth, raw, far.zMid.to_numpy(float)) ** 2
    wn = far.n.to_numpy(float)
    c2 = float(np.sum(wn * x * y) / np.sum(wn * x * x)) if np.sum(wn * x * x) > 0 else 0.0
    return {"bias0M": float(b0), "biasPerM": float(b1), "incidenceK": float(np.sqrt(max(c2, 0.0)))}


def coverage(test: pd.DataFrame, f: dict, raw: bool, fx: float, cfg_depth: dict) -> float | None:
    t = test[test.edgePx >= EDGE_MAX_PX]
    if t.empty:
        return None
    b = f["bias0M"] + f["biasPerM"] * t.Z
    s = np.sqrt(sigma_z(cfg_depth, raw, t.Z) ** 2 + (f["incidenceK"] * t.Z * np.tan(np.radians(np.minimum(t.thetaDeg, 84.26))) / fx) ** 2)
    return float((np.abs(t.e - b) <= s).mean())


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("sessions", nargs="+", type=Path)
    ap.add_argument("--runs", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    cfg_depth = json.loads(DEFAULT_CONFIG.read_text(encoding="utf-8"))["depth"]
    res = {}
    for src in SOURCES:
        raw = src == "RAW"
        df = pd.concat([session_pixels(s, a.runs / s.name / "align.json", src) for s in a.sessions], ignore_index=True)
        fx = depth_k(json.loads((a.sessions[0] / "meta.json").read_text(encoding="utf-8")), 160, 90)["fx"]
        st = bins(df)
        f = fit(st, raw, fx, cfg_depth)
        names = sorted(df.session.unique())
        half = [set(names[0::2]), set(names[1::2])]
        cov = []
        for i in (0, 1):
            fi = fit(bins(df[df.session.isin(half[i])]), raw, fx, cfg_depth)
            cov.append(coverage(df[df.session.isin(half[1 - i])], fi, raw, fx, cfg_depth))
        by_el = {el: dict(zip(("b", "sigma"), robust(g.e.to_numpy()))) | {"n": len(g)} for el, g in df[df.edgePx >= EDGE_MAX_PX].groupby("element")}
        offsets = df.groupby(["session", "element", "face"]).offset.first()
        by_el["offsetRangeM"] = {"min": float(offsets.min()), "max": float(offsets.max())}
        res[src] = {"fit": f, "coverageAonB_BonA": cov, "pixels": len(df), "byElementNoEdge": by_el, "bins": st.to_dict(orient="records")}
        print(f"{src}: 화소 {len(df)}  b(Z) = {f['bias0M']:+.4f} + {f['biasPerM']:+.4f}·Z  c = {f['incidenceK']:.3f}  교차 검사 |e−b|≤σ {cov}")
        for el, v in by_el.items():
            if "n" in v:
                print(f"   {el:10s} n {v['n']:8d}  b {v['b']:+.3f}  σ {v['sigma']:.3f}")
        print(f"   면 상수 범위 {by_el['offsetRangeM']}")
        zb = st[(st.edge == "4+")].groupby("zMid").apply(lambda g: pd.Series({"n": g.n.sum(), "b": np.average(g.b, weights=g.n), "sigma": np.average(g.sigma, weights=g.n)}))
        print(zb.round(3).to_string())
    a.out.mkdir(parents=True, exist_ok=True)
    (a.out / "pixel_error.json").write_text(json.dumps(res, indent=1, ensure_ascii=False, default=str), encoding="utf-8")


if __name__ == "__main__":
    main()
