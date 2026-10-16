"""깊이 오차 모델: 평평한 복도 바닥의 높이 오차를 거리별로 잰다(M13.5 준비, 단차 녹화 없이 잴 수 있는 것).
    python depth_error.py <세션 폴더> [...] [--step N]  → 원천·묶음(조정용/확인용)별 표 출력
- 바닥 점: 정답 좌표 |x| < CORRIDOR_X_M(옆 벽 ±0.52 m), 끝 벽 END_MARGIN_M 앞까지, 물체 바닥 자리 ± OBJECT_MARGIN_M 밖,
  그 프레임 바닥 ± BAND_M
- 높이 기준: 세션 전체에서 카메라 수평 REF_D_M 안 바닥 점의 월드 높이 중앙값(가장 가까운 바닥 = 가장 정확한 바닥). 높이는 월드
  값이라 자세의 높이 드리프트도 들어간다(지도가 보는 그대로). 프레임별 최빈값은 들린 먼 바닥에 끌려 가까운 바닥보다 약 0.15 m 높았다
- 거리별: 중앙값(들림), 강건 σ(IQR/1.349), 꼬리(중앙값에서 TAIL_M 넘게 벗어난 비율)
- 한 장 단차 검출기 흉내: 프레임마다 보행선 방향 CELL_M 칸의 높이 중앙값, 이웃 칸 차(먼 칸 − 가까운 칸)가 −DROP_M보다
  낮은 비율 = 평평한 바닥에서의 오경보
- 융합 칸: 칸마다 여러 프레임 값의 중앙값. 카메라에서 FUSE_MAX_M 안에서 본 값만 쓴
  경우와 전부 쓴 경우의 이웃 칸 차 최솟값
깊이 원천: SMOOTHED, RAW(신뢰도 ≥ 0, ≥ RAW_CONF; M3 F6 결정의 30과 같음)

M30 오차 모델:
    python depth_error.py <세션 폴더> [...] --model <출력 폴더> --runs <기준 재생 폴더>   → depth_model.json, depth_model.png
- 평면 잔차: 바닥(정답 y = 0)과, 정답에 벽이 있으면 옆 벽·끝 벽 앞면의 점. 잔차는 면 법선 방향(카메라 쪽 +).
  정렬은 기준 재생의 align.json(장면 지표와 같은 정렬). 세션·면마다 카메라 깊이 Z 1~2 m 잔차 중앙값(줄자·정렬 상수 오차)을 빼고 따로 적는다
- 카메라 깊이 Z 구간마다 편향 b(Z) = 중앙값, σ(Z) = 1.4826 × MAD(잔차 − b). σ(Z) = σ0 + k·Z²를 점 수 가중 최소제곱으로 맞춘다
  (Khoshelham 2012). 원시 깊이는 신뢰도 4구간별로
- 검사: 세션을 이름 순으로 번갈아 두 묶음으로 나눠, 한 묶음의 b·σ 모델로 다른 묶음의 |잔차 − b| ≤ σ 비율(정규분포면 68%)
    python depth_error.py <세션 폴더> [...] --dup --runs <기준 재생 폴더>   → 깊이 새 시각 비율, 재생이 같은 시각 깊이를 넣은 횟수

M31 보정 녹화 K01(끝 벽까지 거리를 미리 정한 정지 세션):
    python depth_error.py <세션 폴더> [...] --absolute <출력 폴더> --runs <기준 재생 폴더>   → depth_absolute.json, depth_absolute.png
- 면 상수를 빼지 않은 끝 벽 잔차 중앙값 = 그 거리의 절대 편향(원천·원시 신뢰도 4구간별). 같은 거리 세션끼리 차 = 세션 사이 편향.
  사람이 표시에 서는 오차(약 ±3 cm, M12.0)보다 작은 값은 판단하지 않는다
  끝 벽 점은 면 띠 ±MODEL_BAND_M(0.3 m) 안만 쓰므로 그보다 큰 편향은 잘린다(K01은 물체 없는 정면 벽이라 띠 안으로 예상)
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

from align import fit, load_frames, to_truth
from error_decomp import end_face, forward_xz, frame_floor, load_cfg, targets
from metrics import load_truth, scene_of
from PIL import Image

from spike_check import quat_to_rot
from worldpts import DEPTH_COLUMN, depth_k, depth_rows, iter_world, load_depth_m

# 분석 도구 파라미터(앱·core 설정이 아님)
CORRIDOR_X_M = 0.30
END_MARGIN_M = 0.3
OBJECT_MARGIN_M = 0.3
BAND_M = 0.5
TAIL_M = 0.10
CELL_M = 0.25
CELL_MIN_POINTS = 15
DROP_M = (0.05, 0.10)
FUSE_MAX_M = 2.5
FUSE_MIN_OBS = 3
REF_D_M = (1.0, 2.0)
RAW_CONF = 30
D_EDGES = [0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 5.0]
DEV_SCENES = {"E01h", "E02", "S02"}  # 조정용(IMPROVE_SPEC v0.3.8), 나머지는 확인용
VARIANTS = [("SMOOTHED", None), ("RAW", 0), ("RAW", RAW_CONF)]


def world_frames(session: Path, frames: pd.DataFrame, source: str, min_conf: int | None, step: int):
    """(frames.csv 행, 월드 점). RAW는 신뢰도 영상으로 [min_conf] 미만 픽셀을 버린다."""
    for row, p, _ in iter_world(session, frames, source, step, min_conf):
        yield row, p


def floor_mask(x, z, truth: list[dict], z_end: float) -> np.ndarray:
    """보행선 띠 안이고 끝 벽 앞, 물체 바닥 자리 밖."""
    m = (np.abs(x) < CORRIDOR_X_M) & (z < z_end - END_MARGIN_M)
    for o in truth:
        if o["kind"] != "object":
            continue
        mn, mx = o["min"], o["max"]
        m &= ~((x > mn[0] - OBJECT_MARGIN_M) & (x < mx[0] + OBJECT_MARGIN_M) & (z > mn[2] - OBJECT_MARGIN_M) & (z < mx[2] + OBJECT_MARGIN_M))
    return m


def analyze(session: Path, source: str, min_conf: int | None, step: int) -> dict:
    cfg = load_cfg(session)
    frames = load_frames(session)
    tr = frames[frames.tracking == "TRACKING"].reset_index(drop=True)
    data = []
    for row, p in world_frames(session, tr, source, min_conf, step):
        cam = np.array([row.tx, row.ty, row.tz])
        data.append((row, p, frame_floor(p, cam, forward_xz(pd.DataFrame([row]))[0])))
    floor_y = float(np.nanmedian([f for _, _, f in data]))
    al = fit(frames, cfg["align"], cfg["head"]["offsetFromCameraM"], floor_y)
    truth = load_truth(session, cfg)[0] or []
    z_end = end_face(targets(truth))

    pts = []  # 프레임마다 (z, d, 월드 y) 중 바닥 후보
    for row, p, _ in data:
        x, _, z = to_truth(al, p[:, 0], p[:, 1], p[:, 2])
        d = np.hypot(p[:, 0] - row.tx, p[:, 2] - row.tz)
        m = floor_mask(x, z, truth, z_end) & (np.abs(p[:, 1] - floor_y) < 2 * BAND_M) & (d >= D_EDGES[0])
        pts.append((z[m], d[m], p[m, 1]))
    near = np.concatenate([y[(d >= REF_D_M[0]) & (d < REF_D_M[1])] for _, d, y in pts])
    ref = float(np.median(near)) if len(near) else floor_y

    rs, ds, diffs, cells = [], [], [], {}
    for z, d, y in pts:
        r = y - ref
        sel = np.abs(r) < BAND_M
        rs.append(r[sel])
        ds.append(d[sel])
        zc = np.floor(z[sel] / CELL_M).astype(int)
        g = pd.DataFrame({"c": zc, "r": r[sel], "d": d[sel]}).groupby("c").agg(n=("r", "size"), r=("r", "median"), d=("d", "mean"))
        g = g[g.n >= CELL_MIN_POINTS]
        for c, v in g.iterrows():
            cells.setdefault(c, []).append((v.r, v.d))
        idx = set(g.index)
        for c in g.index:
            if c + 1 in idx:
                a, b = g.loc[c], g.loc[c + 1]
                near, far = (a, b) if a.d < b.d else (b, a)
                diffs.append((near.d, far.r - near.r))

    def fused(max_d: float) -> np.ndarray:
        h = {c: np.median([w for w, d in obs if d <= max_d]) for c, obs in cells.items()
             if sum(d <= max_d for _, d in obs) >= FUSE_MIN_OBS}
        return np.array([h[c + 1] - h[c] for c in sorted(h) if c + 1 in h]) if h else np.array([])

    return {"session": session.name, "scene": scene_of(session), "r": np.concatenate(rs), "d": np.concatenate(ds),
            "diffs": np.array(diffs).reshape(-1, 2), "fusedNear": fused(FUSE_MAX_M), "fusedAll": fused(np.inf)}


def robust(v: np.ndarray) -> tuple[float, float, float]:
    """중앙값, 강건 σ, 꼬리 비율."""
    med = float(np.median(v))
    q1, q3 = np.percentile(v, [25, 75])
    return med, float((q3 - q1) / 1.349), float((np.abs(v - med) > TAIL_M).mean())


def table(results: list[dict]) -> None:
    r = np.concatenate([o["r"] for o in results])
    d = np.concatenate([o["d"] for o in results])
    df = np.concatenate([o["diffs"] for o in results])
    print("  거리(m)    점 수   들림(m)  σ(m)  꼬리% | 이웃 칸 차 중앙값  σ   차<−0.05  차<−0.10 (칸 쌍 수)")
    for a, b in zip(D_EDGES[:-1], D_EDGES[1:]):
        m = (d >= a) & (d < b)
        k = (df[:, 0] >= a) & (df[:, 0] < b)
        if m.sum() < 100:
            continue
        med, sig, tail = robust(r[m])
        line = f"  {a:.1f}–{b:.1f}  {m.sum():8d}  {med:+.3f}  {sig:.3f}  {100 * tail:4.1f}"
        if k.sum() >= 20:
            dm, ds, _ = robust(df[k, 1])
            line += f" | {dm:+.3f}  {ds:.3f}  {100 * (df[k, 1] < -DROP_M[0]).mean():5.1f}%  {100 * (df[k, 1] < -DROP_M[1]).mean():5.1f}%  ({k.sum()})"
        print(line)


# M30 오차 모델 파라미터(분석 도구 값, 앱 설정 아님): 면 띠·여유는 위 바닥 분석·MapEval과 같은 0.3 m, 높이는 MapEval의 0.3~1.8 m
MODEL_BAND_M = 0.3
MODEL_WALL_Y_M = (0.3, 1.8)
MODEL_Z_EDGES = np.arange(0.5, 5.01, 0.5)
MODEL_REF_Z_M = (1.0, 2.0)
MODEL_MIN_POINTS = 500
MODEL_SMOOTHED_EVERY = 3  # 평활 깊이는 매 프레임 새 시각이지만 원시 갱신은 약 1/3(--dup): 원시와 비슷한 장 수로
CONF_BINS = [(0, 64), (64, 128), (128, 192), (192, 256)]
INCIDENCE_EDGES_DEG = [0, 30, 60, 90]


def model_points(session: Path, run_dir: Path, source: str, subtract: bool = True) -> pd.DataFrame:
    """평면 잔차 점: 면 이름·종류(floor/wall, floor/sidewall/endwall), 카메라 깊이 Z, 수평 거리 ρ, 입사각, 신뢰도, 잔차(면 상수 뺀 것), 면 상수.
    [subtract]가 거짓이면 면 상수를 빼지 않는다(M31 K01: 거리를 미리 정한 절대 편향)."""
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    cfg = load_cfg(session)
    al = json.loads((run_dir / "align.json").read_text(encoding="utf-8"))
    truth = load_truth(session, cfg)[0] or []
    tg = targets(truth)
    walls = [(n, k, mn, mx) for n, k, mn, mx in tg if k in ("sidewall", "endwall")]
    sides = [(mn, mx) for _, k, mn, mx in walls if k == "sidewall"]
    wl = max([mx[0] for mn, mx in sides if mx[0] < 0], default=None)
    wr = min([mn[0] for mn, mx in sides if mn[0] > 0], default=None)
    ze = end_face(tg)
    half = min(-wl, wr) - MODEL_BAND_M if wl is not None and wr is not None else CORRIDOR_X_M
    frames = load_frames(session)
    col = DEPTH_COLUMN[source]
    tcol = "depthTNs" if source == "SMOOTHED" else "rawDepthTNs"
    rows = depth_rows(frames, source).drop_duplicates(tcol)
    if source == "SMOOTHED":
        rows = rows.iloc[::MODEL_SMOOTHED_EVERY]
    out = []
    k = None
    rot_only = {**al, "origin": [0.0, 0.0], "floorY": 0.0}
    for _, row in rows.iterrows():
        d = load_depth_m(session / row[col])
        conf = np.asarray(Image.open(session / row["confFile"])) if source == "RAW" and isinstance(row.get("confFile"), str) else None
        if k is None or (k["width"], k["height"]) != (d.shape[1], d.shape[0]):
            k = depth_k(meta, d.shape[1], d.shape[0])
        v, u = np.mgrid[0:d.shape[0]:2, 0:d.shape[1]:2]
        dz = d[::2, ::2]
        ok = dz > 0
        zc = dz[ok]
        p_gl = np.stack([(u[ok] - k["cx"]) * zc / k["fx"], -(v[ok] - k["cy"]) * zc / k["fy"], -zc], axis=1)
        r = quat_to_rot(np.array([[row.qx, row.qy, row.qz, row.qw]]))[0]
        cam = np.array([row.tx, row.ty, row.tz])
        pw = p_gl @ r.T + cam
        ray = pw - cam
        ray /= np.linalg.norm(ray, axis=1, keepdims=True)
        x, y, z = to_truth(al, pw[:, 0], pw[:, 1], pw[:, 2])
        rx, ry, rz = to_truth(rot_only, ray[:, 0], ray[:, 1], ray[:, 2])
        cx, _, cz = to_truth(al, cam[0], cam[1], cam[2])
        rho = np.hypot(x - cx, z - cz)
        cf = conf[::2, ::2][ok] if conf is not None and conf.shape == d.shape else np.full(zc.shape, -1)
        fl = (np.abs(y) < MODEL_BAND_M) & (np.abs(x) < half) & (z < ze - MODEL_BAND_M)
        for o in truth:
            if o["kind"] == "object":
                mn, mx = o["min"], o["max"]
                fl &= ~((x > mn[0] - OBJECT_MARGIN_M) & (x < mx[0] + OBJECT_MARGIN_M) & (z > mn[2] - OBJECT_MARGIN_M) & (z < mx[2] + OBJECT_MARGIN_M))
        parts = [("floor", "floor", "floor", fl, y, np.abs(ry))]
        hy = (y > MODEL_WALL_Y_M[0]) & (y < MODEL_WALL_Y_M[1])
        for name, kind, mn, mx in walls:
            if kind == "sidewall":
                inner = mx[0] if mx[0] < 0 else mn[0]
                s = 1.0 if inner < 0 else -1.0  # 복도 안쪽(카메라 쪽) +
                m = hy & (np.abs(x - inner) < MODEL_BAND_M) & (z > mn[2] + MODEL_BAND_M) & (z < min(mx[2], ze) - MODEL_BAND_M)
                parts.append((name, "wall", kind, m, (x - inner) * s, np.abs(rx)))
            else:
                m = hy & (np.abs(z - mn[2]) < MODEL_BAND_M) & (np.abs(x) < half)
                parts.append((name, "wall", kind, m, mn[2] - z, np.abs(rz)))
        for name, surf, fkind, m, res, cosn in parts:
            if m.any():
                out.append(pd.DataFrame({"session": session.name, "face": name, "surface": surf, "faceKind": fkind, "Z": zc[m], "rho": rho[m],
                                         "incDeg": np.degrees(np.arccos(np.clip(cosn[m], 0, 1))), "conf": cf[m], "r": res[m]}))
    if not out:
        return pd.DataFrame()
    df = pd.concat(out, ignore_index=True)
    if not subtract:
        df["offset"] = 0.0
        return df
    # 면마다 Z 1~2 m 중앙값(줄자·정렬 상수)을 뺀다
    ref = df[(df.Z >= MODEL_REF_Z_M[0]) & (df.Z < MODEL_REF_Z_M[1])].groupby("face").r.median()
    df["offset"] = df.face.map(ref)
    df = df[df.offset.notna()].copy()
    df["r"] = df.r - df.offset
    return df


def bin_stats(df: pd.DataFrame) -> pd.DataFrame:
    """Z 구간별 점 수, 편향, σ."""
    rows = []
    for a, b in zip(MODEL_Z_EDGES[:-1], MODEL_Z_EDGES[1:]):
        r = df.r[(df.Z >= a) & (df.Z < b)].to_numpy()
        if len(r) == 0:
            continue
        bias = float(np.median(r))
        rows.append({"zLo": float(a), "zHi": float(b), "zMid": float((a + b) / 2), "n": len(r), "bias": bias,
                     "sigma": float(1.4826 * np.median(np.abs(r - bias)))})
    return pd.DataFrame(rows, columns=["zLo", "zHi", "zMid", "n", "bias", "sigma"])


def fit_sigma(st: pd.DataFrame, power: int = 2) -> tuple[float, float] | None:
    """σ(Z) = σ0 + k·Z^power, 점 수 가중 최소제곱(구간 점 수 ≥ MODEL_MIN_POINTS). power 2 = 삼각측량 모델(설계), 1 = 비교용 선형."""
    s = st[st.n >= MODEL_MIN_POINTS]
    if len(s) < 2:
        return None
    w = np.sqrt(s.n.to_numpy(float))
    a = np.stack([np.ones(len(s)), s.zMid.to_numpy() ** power], axis=1) * w[:, None]
    (s0, kk), *_ = np.linalg.lstsq(a, s.sigma.to_numpy() * w, rcond=None)
    return float(s0), float(kk)


def coverage(train: pd.DataFrame, test: pd.DataFrame, power: int = 2) -> dict | None:
    """[train]의 b(Z)·σ 모델로 [test]의 |잔차 − b| ≤ σ 비율(구간별·전체, 두 쪽 모두 점 수 ≥ MODEL_MIN_POINTS인 구간만)."""
    st = bin_stats(train)
    fs = fit_sigma(st, power)
    if fs is None or test.empty:
        return None
    bins = []
    hit = tot = 0
    for _, b in st.iterrows():
        r = test.r[(test.Z >= b.zLo) & (test.Z < b.zHi)].to_numpy()
        if len(r) < MODEL_MIN_POINTS or b.n < MODEL_MIN_POINTS:
            continue
        sig = fs[0] + fs[1] * b.zMid ** power
        c = int((np.abs(r - b.bias) <= sig).sum())
        bins.append({"zMid": b.zMid, "n": len(r), "within1Sigma": c / len(r)})
        hit += c
        tot += len(r)
    return {"bins": bins, "within1Sigma": hit / tot if tot else None}


def model(sessions: list[Path], runs: Path, out: Path) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    groups = {}
    for source in ("SMOOTHED", "RAW"):
        df = pd.concat([model_points(s, runs / s.name, source) for s in sessions], ignore_index=True)
        half_a = set(sorted(df.session.unique())[0::2])
        sub = {"all": df}
        if source == "RAW":
            sub |= {f"conf{lo}-{hi - 1}": df[(df.conf >= lo) & (df.conf < hi)] for lo, hi in CONF_BINS}
        for cname, cdf in sub.items():
            for surf in ("floor", "wall"):
                d = cdf[cdf.surface == surf]
                if d.empty:
                    continue
                st = bin_stats(d)
                a, b = d[d.session.isin(half_a)], d[~d.session.isin(half_a)]
                inc = []
                for lo, hi in zip(INCIDENCE_EDGES_DEG[:-1], INCIDENCE_EDGES_DEG[1:]):
                    s = bin_stats(d[(d.incDeg >= lo) & (d.incDeg < hi)])
                    inc.append({"loDeg": lo, "hiDeg": hi, "n": int(s.n.sum()),
                                "sigmaMean": float((s.sigma * s.n).sum() / s.n.sum()) if s.n.sum() else None})
                groups[f"{source}/{cname}/{surf}"] = {
                    "sessions": int(d.session.nunique()), "points": len(d),
                    "bins": st.to_dict("records"), "fit": fit_sigma(st),
                    "coverageAonB": coverage(a, b), "coverageBonA": coverage(b, a),
                    "fitLinear": fit_sigma(st, 1), "coverageLinearAonB": coverage(a, b, 1), "coverageLinearBonA": coverage(b, a, 1),
                    "incidence": inc,
                    "offsets": {f"{s} {f}": round(float(o), 4) for (s, f), o in d.groupby(["session", "face"]).offset.first().items()}
                    if cname == "all" else None,
                }
    res = {"zEdges": MODEL_Z_EDGES.tolist(), "minPoints": MODEL_MIN_POINTS, "groups": groups}
    (out / "depth_model.json").write_text(json.dumps(res, indent=1, ensure_ascii=False), encoding="utf-8")
    plot_model(res, out / "depth_model.png")
    return res


def plot_model(res: dict, path: Path) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(2, 2, figsize=(10, 7), sharex=True)
    for j, surf in enumerate(("floor", "wall")):
        for key, ls in (("SMOOTHED/all", "-"), ("RAW/all", "--"), ("RAW/conf192-255", ":"), ("RAW/conf0-63", "-.")):
            g = res["groups"].get(f"{key}/{surf}")
            if not g:
                continue
            b = pd.DataFrame(g["bins"])
            b = b[b.n >= MODEL_MIN_POINTS]
            axes[0, j].plot(b.zMid, b.bias, ls, color="k", label=key)
            axes[1, j].plot(b.zMid, b.sigma, ls, color="k", marker="o", ms=3, label=key)
            if g["fit"]:
                z = np.linspace(0.5, 5, 50)
                axes[1, j].plot(z, g["fit"][0] + g["fit"][1] * z ** 2, ls, color="0.6", lw=1)
        axes[0, j].set_title(f"{surf}: bias b(Z) (m)")
        axes[1, j].set_title(f"{surf}: sigma(Z) (m), gray = sigma0 + k Z^2")
        axes[1, j].set_xlabel("camera depth Z (m)")
        for ax in axes[:, j]:
            ax.grid(alpha=0.3)
    axes[0, 0].legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(path, dpi=130)


def absolute(sessions: list[Path], runs: Path) -> list[dict]:
    """M31 K01: 끝 벽까지 거리를 미리 정한 정지 세션마다 끝 벽 잔차(카메라 쪽 +)의 중앙값 = 그 거리의 절대 편향.
    면 상수를 빼지 않는다. 거리는 끝 벽 점 카메라 깊이 Z 중앙값의 0.5 m 단위 반올림(K01 1.0·1.5·2.0·3.0·4.0 m)."""
    rows = []
    for s in sessions:
        for source in ("SMOOTHED", "RAW"):
            df = model_points(s, runs / s.name, source, subtract=False)
            e = df[df.faceKind == "endwall"] if not df.empty else df
            subs = {source: e}
            if source == "RAW" and not e.empty:
                subs |= {f"RAW conf{lo}-{hi - 1}": e[(e.conf >= lo) & (e.conf < hi)] for lo, hi in CONF_BINS}
            for name, d in subs.items():
                if len(d) < MODEL_MIN_POINTS:
                    continue
                bias = float(np.median(d.r))
                rows.append({"session": s.name, "source": name, "zNominal": round(float(np.median(d.Z)) * 2) / 2,
                             "zMedian": float(np.median(d.Z)), "n": len(d), "bias": bias,
                             "sigma": float(1.4826 * np.median(np.abs(d.r - bias)))})
    return rows


def pair_diffs(rows: list[dict]) -> list[dict]:
    """같은 원천·같은 거리 세션끼리 편향 차(최대 − 최소) = 세션 사이 편향."""
    df = pd.DataFrame(rows)
    out = []
    for (src, zn), g in df.groupby(["source", "zNominal"]):
        if len(g) >= 2:
            out.append({"source": src, "zNominal": float(zn), "sessions": len(g), "biasSpread": float(g.bias.max() - g.bias.min()),
                        "biasMean": float(g.bias.mean())})
    return out


def plot_absolute(rows: list[dict], path: Path) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    df = pd.DataFrame(rows)
    fig, ax = plt.subplots(figsize=(7, 4))
    for src, mk in (("SMOOTHED", "o"), ("RAW", "s"), ("RAW conf192-255", "^"), ("RAW conf0-63", "v")):
        d = df[df.source == src]
        if not d.empty:
            ax.plot(d.zMedian, d.bias, mk, color="k", mfc="none" if "conf" in src else "k", label=src)
    ax.axhline(0, color="0.6", lw=1)
    ax.set_xlabel("camera depth Z of end wall (m)")
    ax.set_ylabel("absolute bias (m, + = in front of wall)")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(path, dpi=130)


def dup(sessions: list[Path], runs: Path) -> list[dict]:
    """깊이 새 시각 비율(행 대비)과 재생이 같은 시각 깊이를 지도에 넣은 횟수."""
    out = []
    for s in sessions:
        f = load_frames(s)
        sp_file = runs / s.name / "slow_path.csv"
        sp = pd.read_csv(sp_file) if sp_file.is_file() else None
        out.append({"session": s.name, "scene": scene_of(s), "rows": len(f),
                    "smoothedNew": f.depthTNs.dropna().nunique() / len(f), "rawNew": f.rawDepthTNs.dropna().nunique() / len(f),
                    "slowSteps": None if sp is None else len(sp),
                    "slowRepeatedT": None if sp is None else int(sp.tCaptureNs.duplicated().sum())})
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("sessions", nargs="+", type=Path)
    ap.add_argument("--step", type=int, default=2)
    ap.add_argument("--model", type=Path, help="M30 오차 모델 출력 폴더")
    ap.add_argument("--dup", action="store_true", help="M30 새 관측 비율")
    ap.add_argument("--absolute", type=Path, help="M31 K01 절대 편향 출력 폴더")
    ap.add_argument("--runs", type=Path, help="기준 재생 폴더(<runs>/<세션ID>/align.json, slow_path.csv)")
    a = ap.parse_args()
    if a.model:
        res = model(a.sessions, a.runs, a.model)
        for key, g in res["groups"].items():
            def cov(names):
                return [None if not g[c] or g[c]["within1Sigma"] is None else round(g[c]["within1Sigma"], 3) for c in names]
            fit = "–" if not g["fit"] else f"σ0 {g['fit'][0]:+.3f} k {g['fit'][1]:.4f}"
            lin = "–" if not g["fitLinear"] else f"σ0 {g['fitLinear'][0]:+.3f} k {g['fitLinear'][1]:.4f}"
            print(f"{key:28s} 세션 {g['sessions']:2d} 점 {g['points']:8d}  Z² {fit} ±1σ {cov(('coverageAonB', 'coverageBonA'))}"
                  f"  | Z {lin} ±1σ {cov(('coverageLinearAonB', 'coverageLinearBonA'))}")
        return
    if a.absolute:
        rows = absolute(a.sessions, a.runs)
        pairs = pair_diffs(rows)
        a.absolute.mkdir(parents=True, exist_ok=True)
        (a.absolute / "depth_absolute.json").write_text(json.dumps({"rows": rows, "pairs": pairs}, indent=1, ensure_ascii=False), encoding="utf-8")
        plot_absolute(rows, a.absolute / "depth_absolute.png")
        for r in rows:
            print(f"{r['session']}  {r['source']:16s} Z {r['zMedian']:.2f} (≈{r['zNominal']:.1f})  점 {r['n']:7d}  편향 {r['bias']:+.3f}  σ {r['sigma']:.3f}")
        for p in pairs:
            print(f"{p['source']:16s} {p['zNominal']:.1f} m  세션 {p['sessions']}  편향 평균 {p['biasMean']:+.3f}  세션 사이 차 {p['biasSpread']:.3f}")
        return
    if a.dup:
        for o in dup(a.sessions, a.runs):
            print(f"{o['scene']:>5} {o['session']}  행 {o['rows']:4d}  평활 새 시각 {o['smoothedNew']:.2f}  원시 {o['rawNew']:.2f}  "
                  f"느린 경로 {o['slowSteps']}  같은 시각 반복 {o['slowRepeatedT']}")
        return
    for source, conf in VARIANTS:
        res = [analyze(s, source, conf, a.step) for s in a.sessions]
        name = source + ("" if conf is None else f" 신뢰도≥{conf}")
        for group in ("조정용", "확인용"):
            sub = [o for o in res if (o["scene"] in DEV_SCENES) == (group == "조정용")]
            if not sub:
                continue
            print(f"\n## {name} · {group} ({', '.join(o['scene'] + ' ' + o['session'][9:15] for o in sub)})")
            table(sub)
        print(f"\n## {name} · 융합 칸(이웃 칸 차 최솟값 m, 칸 쌍 수): {FUSE_MAX_M} m 안에서 본 값만 / 전부")
        for o in res:
            fn, fa = o["fusedNear"], o["fusedAll"]
            fmt = lambda v: f"{v.min():+.3f} ({len(v)})" if len(v) else "–"
            print(f"  {o['scene']:>18} {o['session'][9:15]}  {fmt(fn):>14}  {fmt(fa):>14}")


if __name__ == "__main__":
    main()
