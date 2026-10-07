"""오차 분해 지표 (M12.0, IMPROVE_SPEC §11.1). 지도가 아니라 프레임별 월드 점(`worldpts.py`)과 자세 기록으로 잰다.

    python error_decomp.py <세션 폴더> [...] [--runs <재생 로그 루트>]
        → <세션>/error_decomp.json, <세션>/error_decomp_frames.csv, 요약 표 출력.
        --runs가 있으면 <루트>/<세션ID>/default/slow_path.csv(`:core:replay`)의 core 바닥 추정 흔들림도 적는다.

- T1 한 프레임: 정답 물체 앞면·끝 벽·옆 벽까지 프레임마다 잰 위치 − 정답(m, + = 카메라에서 멀리 또는 복도 바깥으로)
- T2 정지 누적: 같은 값의 시간 변화와 수렴 시각, 프레임별 바닥 높이의 흔들림, 헛 점 비율(정답상 빈 곳에 찍힌 점),
  유효 깊이 중앙값(정답·바닥 없이 같은 배치의 세션끼리 비교: 수렴하지 않은 깊이를 드러낸다)
- T3 단방향 보행: 걸은 거리 구간별 오차와 기울기, 자세 점프(frames.csv)
- T4 왕복: 구간(leg)마다 옆 벽이 정답보다 옆으로 옮겨져 보인 양(좌우 벽 오차의 차 ÷ 2)과 폭 오차(합 ÷ 2)를 구간끼리 비교,
  구간 사이 회전 각, 시작 자리로 돌아왔을 때의 루프 닫힘. 세로 파지 시야(좌우 ±20°)로는 옆 벽이 약 1.4 m 앞부터 보여 갈 때와
  올 때 본 벽 구간이 거의 겹치지 않으므로, 같은 구간을 직접 맞대지 않고 정답을 공통 기준으로 쓴다
정답 좌표·정렬은 `align.py`(정지 녹화는 카메라 시선), 정답 파일은 v2(`kind: structure`가 벽). 바닥 높이는 core 바닥 추정이 아니라
이 도구가 프레임마다 깊이 점의 최빈 높이로 따로 잰다(core 추정도 오차원이라서).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from align import DEFAULT_CONFIG, fit, load_frames, to_truth
from metrics import load_truth, scene_of
from spike_check import quat_to_rot
from worldpts import iter_world

# 분석 도구 파라미터(앱·core 설정이 아님)
JUMP_STEP_M = 0.15  # 한 프레임 이동이 이보다 크면 자세 점프(1 m/s 보행은 프레임당 약 3 cm, M12.0 실측 정상 프레임 최대 6.5 cm)
JUMP_ROT_DEG = 12.0  # 한 프레임 회전이 이보다 크면 자세 점프(M12.0 실측 정상 프레임 최대 7°)
FLOOR_BELOW_CAMERA_M = 0.3  # 바닥 후보: 카메라보다 이만큼 아래
FLOOR_RADIUS_M = 3.0  # 바닥 후보: 카메라에서 수평 이 거리 안
FLOOR_BAND_M = 0.4  # 바닥 후보: 카메라 시선(수평) 좌우 이 거리 안(복도 옆 벽 아랫부분이 최빈값을 차지하지 않게)
FLOOR_BIN_M = 0.02
FLOOR_PEAK_RATIO = 3.0  # 최빈 칸이 점이 있는 칸 평균의 이 배 이상일 때만 바닥(벽만 보이면 높이가 고르게 퍼진다)
FACE_BIN_M = 0.02  # 면 위치 = 고른 점의 최빈 칸 주변 평균(중앙값은 막 점에 끌려간다)
MARGIN_M = 0.10  # 헛 점: 정답 면·상자에서 이만큼 떨어진 빈 곳의 점
GHOST_MIN_Y_M = 0.3  # 헛 점은 이 높이 위만 센다(걷는 중 바닥 높이를 0.2 m 안팎 틀려도 바닥 점을 헛 점으로 세지 않게)
WALL_Y_M = (0.3, 1.8)  # 벽 점으로 쓰는 높이(바닥·천장 근처 제외)
MIN_POINTS = 20
MOVE_MPS = 0.25  # 보행선 방향 속도가 이보다 크면 걷는 중
SMOOTH_S = 0.5
MIN_LEG_S = 1.0
CONV_TOL_M = 0.05  # 수렴: 1 s 중앙값이 마지막 5 s 중앙값에서 이 안에 머무름
CONV_FINAL_S = 5.0
CURVE_BIN_M = 0.5
CLOSE_WINDOW_S = 0.5


def load_cfg(session: Path) -> dict:
    """기본 설정 + 세션의 파지 오프셋(`meta.gripOffsetM`, 실제 녹화는 설정과 같고 합성은 장면마다 다름)."""
    cfg = json.loads(DEFAULT_CONFIG.read_text(encoding="utf-8"))
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    if meta.get("gripOffsetM"):
        cfg["head"]["offsetFromCameraM"] = list(meta["gripOffsetM"])
    return cfg


def forward_xz(df: pd.DataFrame) -> np.ndarray:
    """카메라 시선(GL −Z)의 수평 단위 벡터 (N, 2) = (x, z)."""
    x, y, z, w = (df[c].to_numpy() for c in ("qx", "qy", "qz", "qw"))
    f = np.stack([-2 * (x * z + y * w), -(1 - 2 * (x * x + y * y))], axis=1)
    return f / np.linalg.norm(f, axis=1, keepdims=True)


def pose_jumps(tr: pd.DataFrame, t: np.ndarray) -> list[dict]:
    """한 프레임 사이 이동·회전이 기준보다 큰 곳(추적 중 월드 좌표 재정렬)."""
    p = tr[["tx", "ty", "tz"]].to_numpy()
    r = quat_to_rot(tr[["qx", "qy", "qz", "qw"]].to_numpy())
    step = np.linalg.norm(np.diff(p, axis=0), axis=1)
    rot = np.degrees(np.arccos(np.clip((np.einsum("nij,nij->n", r[:-1], r[1:]) - 1) / 2, -1, 1)))
    idx = np.nonzero((step > JUMP_STEP_M) | (rot > JUMP_ROT_DEG))[0]
    return [{"tS": float(t[i + 1]), "stepM": float(step[i]), "rotDeg": float(rot[i])} for i in idx]


def mode_mean(v: np.ndarray, bin_m: float) -> tuple[float, float]:
    """최빈 칸(같으면 낮은 칸) 주변 ±1.5칸 평균과, 최빈 칸 개수 ÷ 점이 있는 칸의 평균 개수(뾰족함)."""
    bins, counts = np.unique(np.floor(v / bin_m), return_counts=True)
    c = (bins[counts.argmax()] + 0.5) * bin_m
    return float(v[np.abs(v - c) < 1.5 * bin_m].mean()), float(counts.max() / counts.mean())


def frame_floor(pts: np.ndarray, cam: np.ndarray, fwd: np.ndarray) -> float:
    """깊이 한 장의 바닥 높이(월드 y): 카메라 아래, 시선 앞 띠 안 점 높이의 최빈값(core `Floor`와 같은 생각). 바닥이 안 보이면 nan."""
    dx, dz = pts[:, 0] - cam[0], pts[:, 2] - cam[2]
    near = (dx**2 + dz**2 < FLOOR_RADIUS_M**2) & (np.abs(dx * fwd[1] - dz * fwd[0]) < FLOOR_BAND_M)
    y = pts[(pts[:, 1] < cam[1] - FLOOR_BELOW_CAMERA_M) & near, 1]
    if len(y) < MIN_POINTS:
        return float("nan")
    fy, peak = mode_mean(y, FLOOR_BIN_M)
    # 최빈값이 후보 위 끝에 붙으면 바닥이 아니라 벽을 비스듬히 본 점
    if peak < FLOOR_PEAK_RATIO or fy > cam[1] - FLOOR_BELOW_CAMERA_M - 2 * FLOOR_BIN_M:
        return float("nan")
    return fy


def head_xz(xz: np.ndarray, fwd: np.ndarray, head: list[float]) -> np.ndarray:
    """머리 수평 위치 = 카메라 + 오프셋(시선 방향 기준, 오른쪽·앞). 제자리에서 돌면 카메라는 머리 둘레로 돌지만 머리는 그대로다."""
    right = np.stack([-fwd[:, 1], fwd[:, 0]], axis=1)
    return xz + right * head[0] + fwd * head[2]


def find_legs(t: np.ndarray, z: np.ndarray) -> list[dict]:
    """보행선(정답 z) 방향 머리 속도의 부호가 같은 걷는 구간. dir +1 = 갈 때, −1 = 올 때."""
    n = max(1, int(round(SMOOTH_S / np.median(np.diff(t)))))
    zs = pd.Series(z).rolling(n, center=True, min_periods=1).median().to_numpy()
    v = pd.Series(np.gradient(zs, t)).rolling(n, center=True, min_periods=1).median().to_numpy()
    sgn = np.where(v > MOVE_MPS, 1, np.where(v < -MOVE_MPS, -1, 0))
    runs, start = [], None
    for i in range(len(sgn) + 1):
        s = sgn[i] if i < len(sgn) else 0
        if start is not None and s != sgn[start]:
            runs.append([sgn[start], t[start], t[i - 1]])
            start = None
        if start is None and s != 0:
            start = i
    merged = []
    for r in runs:  # 같은 방향 사이의 짧은 멈춤은 한 구간
        if merged and merged[-1][0] == r[0] and r[1] - merged[-1][2] < SMOOTH_S:
            merged[-1][2] = r[2]
        else:
            merged.append(r)
    return [{"dir": int(d), "tStartS": float(a), "tEndS": float(b)} for d, a, b in merged if b - a >= MIN_LEG_S]


def targets(truth: list[dict]) -> list[tuple[str, str, np.ndarray, np.ndarray]]:
    """정답에서 잴 대상: 물체 앞면(front), 보행선을 가로막는 벽(endwall), 보행선과 나란한 벽(sidewall)."""
    out = []
    for o in truth:
        mn, mx = np.asarray(o["min"], float), np.asarray(o["max"], float)
        ext = mx - mn
        if o["kind"] == "object":
            out.append((o["name"], "front", mn, mx))
        elif ext[2] < 0.5 <= ext[0]:
            out.append((o["name"], "endwall", mn, mx))
        elif ext[0] < 0.5 <= ext[2]:
            out.append((o["name"], "sidewall", mn, mx))
    return out


def end_face(tg) -> float:
    """보행선을 가로막는 벽의 앞면(정답 z). 없으면 무한."""
    return min([mn[2] for _, k, mn, _ in tg if k == "endwall"], default=float("inf"))


def select(kind: str, mn, mx, x, y, z, cam_z: float, z_end: float):
    """대상 면의 점 선택과 (값, 정답): 값 − 정답이 오차(+ = 멀리/바깥). 옆 벽은 끝 벽 모서리 근처(0.3 m)를 뺀다."""
    if kind in ("front", "endwall"):
        if cam_z > mn[2] - 0.3:  # 면을 앞에서 보고 있을 때만
            return None
        if kind == "front":
            w, h = mx[0] - mn[0], mx[1] - mn[1]
            sel = (x > mn[0] + 0.25 * w) & (x < mx[0] - 0.25 * w) & (y > mn[1] + 0.2 * h) & (y < mx[1] - 0.2 * h)
            sel &= np.abs(z - mn[2]) < 0.5
        else:
            sel = (x > mn[0] + 0.15) & (x < mx[0] - 0.15) & (y > max(mn[1], WALL_Y_M[0])) & (y < min(mx[1], WALL_Y_M[1]))
            sel &= np.abs(z - mn[2]) < 0.6
        return sel, z, mn[2]
    right = (mn[0] + mx[0]) / 2 > 0
    face = mn[0] if right else mx[0]
    sel = (np.abs(x - face) < 0.25) & (y > WALL_Y_M[0]) & (y < WALL_Y_M[1]) & (z > max(mn[2], 0.0)) & (z < min(mx[2], z_end) - 0.3)
    return sel, (x if right else -x), (face if right else -face)


def ghost_fraction(x, y, z, tg) -> float:
    """정답상 빈 곳(옆 벽 사이, 시작 표시와 끝 벽 사이, 바닥 위, 물체 밖, 각각 MARGIN_M 여유)에 찍힌 점의 비율. 바닥 점은 세지 않는다."""
    sides = [(mn, mx) for _, k, mn, mx in tg if k == "sidewall"]
    if len(sides) < 2:
        return float("nan")
    xl = max(mx[0] for mn, mx in sides if mn[0] + mx[0] < 0)
    xr = min(mn[0] for mn, mx in sides if mn[0] + mx[0] > 0)
    ze = min(end_face(tg), min(mx[2] for mn, mx in sides))
    band = (y > GHOST_MIN_Y_M) & (y < 2.0) & (z > 0)
    region = band & (x > xl - MARGIN_M) & (x < xr + MARGIN_M) & (z < ze + MARGIN_M)
    free = band & (x > xl + MARGIN_M) & (x < xr - MARGIN_M) & (z < ze - MARGIN_M)
    for _, k, mn, mx in tg:
        if k == "front":
            free &= ~((x > mn[0] - MARGIN_M) & (x < mx[0] + MARGIN_M) & (y < mx[1] + MARGIN_M) & (z > mn[2] - MARGIN_M) & (z < mx[2] + MARGIN_M))
    n = region.sum()
    return float(free.sum() / n) if n >= MIN_POINTS else float("nan")


def convergence(t: np.ndarray, e: np.ndarray) -> dict | None:
    """정지 녹화의 수렴: 1 s 중앙값이 마지막 CONV_FINAL_S 중앙값에서 CONV_TOL_M 안에 들어와 끝까지 머문 시각(시작부터 s)."""
    ok = ~np.isnan(e)
    t, e = t[ok], e[ok]
    if len(t) < MIN_POINTS or t[-1] - t[0] < 2 * CONV_FINAL_S:
        return None
    final = float(np.median(e[t > t[-1] - CONV_FINAL_S]))
    n = max(1, int(round(1.0 / np.median(np.diff(t)))))
    es = pd.Series(e).rolling(n, center=True, min_periods=1).median().to_numpy()
    bad = np.abs(es - final) > CONV_TOL_M
    conv = float(t[bad].max() - t[0]) if bad.any() else 0.0
    return {"finalM": final, "convergedS": conv, "converged": bool(conv < t[-1] - t[0] - CONV_FINAL_S)}



def stats(v) -> dict | None:
    v = np.asarray(v, float)
    v = v[~np.isnan(v)]
    if not len(v):
        return None
    return {"p10": float(np.percentile(v, 10)), "p50": float(np.median(v)), "p90": float(np.percentile(v, 90)), "n": int(len(v))}


def circ_mean_deg(f: np.ndarray) -> float:
    m = f.mean(axis=0)
    return float(np.degrees(np.arctan2(m[0], -m[1])))  # 월드 −Z에서 +X 쪽 각(core headingDeg와 같음)


def wrap(a: float) -> float:
    return (a + 180.0) % 360.0 - 180.0


def core_floor(run_dir: Path) -> dict | None:
    """core 바닥 추정(`slow_path.csv` floorY)의 흔들림(p90 − p10). 재생 로그가 없으면 None."""
    f = run_dir / "slow_path.csv"
    if not f.is_file():
        return None
    y = pd.read_csv(f).floorY.dropna()
    return {"p50": float(y.median()), "spreadM": float(y.quantile(0.9) - y.quantile(0.1))} if len(y) else None


def analyze(session: Path, step: int = 2, run_dir: Path | None = None) -> dict:
    cfg = load_cfg(session)
    head = cfg["head"]["offsetFromCameraM"]
    frames = load_frames(session)
    tr = frames[frames.tracking == "TRACKING"].reset_index(drop=True)
    t0 = tr.tNs.iloc[0]
    t = (tr.tNs.to_numpy() - t0) / 1e9
    jumps = pose_jumps(tr, t)
    jump_t = [j["tS"] for j in jumps]

    # 1차: 프레임별 월드 점과 바닥 높이
    rows, pts, floors, med_depth = [], [], [], []
    for row, p, d in iter_world(session, tr, cfg["depth"]["source"], step):
        cam = np.array([row.tx, row.ty, row.tz])
        rows.append(row)
        pts.append(p)
        floors.append(frame_floor(p, cam, forward_xz(pd.DataFrame([row]))[0]))
        med_depth.append(float(np.median(d[d > 0])) if (d > 0).any() else float("nan"))
    floors = np.array(floors)
    floor_y = float(np.nanmedian(floors))
    al = fit(frames, cfg["align"], head, floor_y)
    truth, estimated = load_truth(session, cfg)
    tg = targets(truth or [])

    # 정답 좌표의 머리 궤적과 걷는 구간
    fwd = forward_xz(tr)
    xz = tr[["tx", "tz"]].to_numpy()
    hxz = head_xz(xz, fwd, head)
    _, _, hz = to_truth(al, hxz[:, 0], np.zeros(len(hxz)), hxz[:, 1])
    legs = find_legs(t, hz)
    for leg in legs:
        leg["segment"] = sum(j <= leg["tStartS"] for j in jump_t)

    # 2차: 프레임별 오차
    z_end = end_face(tg)
    recs = []
    for row, p, fy, md in zip(rows, pts, floors, med_depth):
        ts = (row.tNs - t0) / 1e9
        x, y, z = to_truth(al, p[:, 0], p[:, 1], p[:, 2])
        if not np.isnan(fy):
            y = y - (fy - floor_y)  # 높이는 그 프레임에서 본 바닥 기준(자세의 높이 드리프트를 지운다)
        _, _, camz = to_truth(al, row.tx, row.ty, row.tz)
        rec = {"tS": ts, "camZ": float(camz), "floorDy": fy - floor_y, "medDepthM": md, "ghost": ghost_fraction(x, y, z, tg)}
        for name, kind, mn, mx in tg:
            s = select(kind, mn, mx, x, y, z, float(camz), z_end)
            rec[name] = mode_mean(s[1][s[0]], FACE_BIN_M)[0] - s[2] if s is not None and s[0].sum() >= MIN_POINTS else float("nan")
        recs.append(rec)
    df = pd.DataFrame(recs)
    df.to_csv(session / "error_decomp_frames.csv", index=False)

    out = {
        "session": session.name,
        "scene": scene_of(session),
        "estimatedTruth": estimated,
        "nDepthFrames": len(df),
        "durationS": float(t[-1]),
        "align": {k: al[k] for k in ("byHeading", "fitShort", "fitLengthM", "residualRmsM", "angleUncertaintyDeg")},
        # 프레임별 바닥 높이의 흔들림(p90 − p10)과 바닥을 못 잡은 프레임 비율
        "floor": {"y": floor_y, "spreadM": float(np.nanpercentile(floors, 90) - np.nanpercentile(floors, 10)),
                  "p10M": float(np.nanpercentile(floors, 10) - floor_y), "p90M": float(np.nanpercentile(floors, 90) - floor_y),
                  "missingFraction": float(np.isnan(floors).mean())},
        "coreFloor": core_floor(run_dir) if run_dir else None,
        "jumps": jumps,
        "ghost": stats(df.ghost),
        "medDepthM": stats(df.medDepthM),
        "targets": {},
    }
    first = legs[0]["tStartS"] if legs else float("inf")
    for name, kind, _, _ in tg:
        e = df[name].to_numpy()
        still = df.tS.to_numpy() < first
        out["targets"][name] = {
            "kind": kind, "all": stats(e), "standing": stats(e[still]), "walking": stats(e[~still]),
            "convergence": convergence(df.tS.to_numpy()[still], e[still]),
        }

    # T3: 첫 갈 때 구간까지 걸은 거리별 오차와 기울기
    if legs and legs[0]["dir"] == 1:
        seg = df[df.tS <= legs[0]["tEndS"]]
        dist = seg.camZ - seg.camZ.iloc[0]
        curve = {}
        for name, _, _, _ in tg:
            ok = seg[name].notna() & (dist >= 0)
            if ok.sum() < 5:
                continue
            bins = (dist[ok] // CURVE_BIN_M) * CURVE_BIN_M
            slope = float(np.polyfit(dist[ok], seg[name][ok], 1)[0]) if np.ptp(dist[ok]) > 0.5 else None
            curve[name] = {"slopeMPerM": slope,
                           "bins": [{"fromM": float(b), "errM": float(v)} for b, v in seg[name][ok].groupby(bins).median().items()]}
        out["walkCurve"] = curve

    # T4: 구간별 옆 벽(바깥 + 오차의 중앙값 → 옆 이동·폭 오차), 회전 각(머리 궤적 방향), 루프 닫힘
    sides = {("right" if mn[0] + mx[0] > 0 else "left"): n for n, k, mn, mx in tg if k == "sidewall"}
    for leg in legs:
        m = (df.tS >= leg["tStartS"]) & (df.tS <= leg["tEndS"])
        e = {lr: float(np.nanmedian(df[n][m])) for lr, n in sides.items() if df[n][m].notna().sum() >= 3}
        leg["sideErrM"] = e
        if len(e) == 2:
            leg["shiftM"] = (e["right"] - e["left"]) / 2  # + = 두 벽이 정답 오른쪽(+x)으로 옮겨져 보임(자세 오차)
            leg["widthErrM"] = (e["right"] + e["left"]) / 2  # + = 복도가 넓게 보임(깊이 오차)
        mid = (t >= leg["tStartS"] + 0.2 * (leg["tEndS"] - leg["tStartS"])) & (t <= leg["tEndS"] - 0.2 * (leg["tEndS"] - leg["tStartS"]))
        c = hxz[mid] - hxz[mid].mean(axis=0)
        d = np.linalg.svd(c)[2][0]
        if np.dot(hxz[mid][-1] - hxz[mid][0], d) < 0:
            d = -d
        leg["dirXZ"] = d.tolist()
    out["legs"] = legs
    out["turnsDeg"] = [float(np.degrees(np.arccos(np.clip(np.dot(a["dirXZ"], b["dirXZ"]), -1, 1)))) for a, b in zip(legs, legs[1:])]
    gaps = []
    for i, leg in enumerate(legs[1:], 1):
        for j in sorted({0, next(k for k, l in enumerate(legs) if l["segment"] == leg["segment"])}):
            if j == i:
                continue
            if "shiftM" in leg and "shiftM" in legs[j]:
                gaps.append({"leg": i, "vsLeg": j, "dShiftM": leg["shiftM"] - legs[j]["shiftM"],
                             "dWidthM": leg["widthErrM"] - legs[j]["widthErrM"], "acrossJump": leg["segment"] != legs[j]["segment"]})
    out["wallGap"] = gaps

    # 루프 닫힘: 처음 정지 구간과, 올 때 구간이 끝난 직후 CLOSE_WINDOW_S(다음 구간·자세 점프 전까지)의 카메라·머리 위치와 시선.
    # 연속 왕복은 표시 위에서 바로 다시 돌아서므로 창을 짧게 둔다
    def window(a: float, b: float) -> np.ndarray:
        cut = min([j for j in jump_t if j > a], default=float("inf"))
        m = (t >= a) & (t < min(b, cut, a + CLOSE_WINDOW_S))
        return m if m.sum() >= 3 else (np.abs(t - a) == np.abs(t - a).min())

    def place(m) -> tuple[np.ndarray, np.ndarray, float]:
        return xz[m].mean(axis=0), hxz[m].mean(axis=0), circ_mean_deg(fwd[m])

    closures = []
    if legs:
        c0, h0, y0 = place((t < legs[0]["tStartS"]) & (t < min(jump_t, default=float("inf"))))
        for i, leg in enumerate(legs):
            if leg["dir"] != -1:
                continue
            nxt = legs[i + 1]["tStartS"] if i + 1 < len(legs) else float(t[-1]) + 1
            c1, h1, y1 = place(window(leg["tEndS"], nxt))
            closures.append({"afterLeg": i, "tS": leg["tEndS"], "cameraM": float(np.linalg.norm(c1 - c0)),
                             "headM": float(np.linalg.norm(h1 - h0)), "yawFrom180Deg": 180.0 - abs(wrap(y1 - y0)),
                             "acrossJump": any(leg["tEndS"] >= j for j in jump_t)})
    out["loopClosure"] = closures
    (session / "error_decomp.json").write_text(json.dumps(out, indent=2, ensure_ascii=False), encoding="utf-8")
    return out


def fmt(v, nd=2) -> str:
    return "—" if v is None or (isinstance(v, float) and np.isnan(v)) else f"{v:+.{nd}f}"


def summary_line(o: dict) -> str:
    """세션 한 줄: 장면, 바닥 흔들림, 헛 점, 대상별 오차 중앙값, 점프, 회전, 루프 닫힘, 벽 간격."""
    tg = "; ".join(f"{n} {fmt((v['all'] or {}).get('p50'))}" for n, v in o["targets"].items())
    conv = "; ".join(f"{n} {v['convergence']['convergedS']:.1f}s{'' if v['convergence']['converged'] else '(미수렴)'}"
                     for n, v in o["targets"].items() if v["convergence"])
    gap = "; ".join(f"L{g['leg']}-{g['vsLeg']} 옆 {g['dShiftM']:+.2f} 폭 {g['dWidthM']:+.2f}{'(점프)' if g['acrossJump'] else ''}" for g in o["wallGap"])
    lc = "; ".join(f"cam {c['cameraM']:.2f} head {c['headM']:.2f} yaw {c['yawFrom180Deg']:+.1f}°" for c in o["loopClosure"])
    cf = f" (core {o['coreFloor']['spreadM']:.2f})" if o.get("coreFloor") else ""
    return (f"| {o['session']} | {o['scene']} | {o['floor']['spreadM']:.2f}{cf} | {fmt((o['ghost'] or {}).get('p50'))} | {tg} | {conv or '—'} | "
            f"{len(o['jumps'])} | {', '.join(f'{x:.1f}' for x in o['turnsDeg']) or '—'} | {lc or '—'} | {gap or '—'} |")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")  # Windows 콘솔(cp949)에서도 한글·기호 출력
    args = sys.argv[1:]
    runs = None
    if "--runs" in args:
        i = args.index("--runs")
        runs = Path(args[i + 1])
        del args[i:i + 2]
    if not args:
        sys.exit(__doc__)
    print("| 세션 | 장면 | 바닥 흔들림 m | 헛 점 비율 | 대상별 오차 중앙값 m | 수렴 | 점프 | 회전 ° | 루프 닫힘 | 벽 간격 |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for s in args:
        print(summary_line(analyze(Path(s), run_dir=runs / Path(s).name / "default" if runs else None)), flush=True)
