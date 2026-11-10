"""실외 클릭 정답 채점 (IMPROVE_SPEC §9.5, M47).

    python event_eval.py <세션 폴더> <재생 폴더>   → <재생 폴더>/event_eval.json, 요약 출력
    python event_eval.py <세션 폴더>               → 클릭 검증만

정답은 세션의 `annotations/events.json`(viewer 클릭 모드, 세계 점 계산은 core `ClickTruth`)에서 세계 점만 읽는다. 채점은 세계 좌표(ARCore 월드):
블록마다 머리 = 카메라 + `head.offsetFromCameraM`(오른쪽, 위, 앞)을 정답 진행 방향으로 돌린 값, 정답 진행 방향 = 앞뒤 HALF_WIN_S 동안 카메라
수평 이동이 MOVE_MIN_M을 넘으면 그 방향, 아니면 카메라 정면. 통로는 이 방향으로 돌리고 정답의 진행 방향 거리는 `metrics.truth_nearest`와 같은
규칙(움직이는 물체는 반지름만큼 앞 가장자리, 선 물체는 LINE_STEP_M 간격 표본). 정답은 알고리즘이 아니므로 앞뒤 시각을 모두 쓴다.
- ① STOP TTC: 움직이는 물체가 STOP 구간(통로 안 거리 < stopM)에 들어갈 때마다, 그 접근(거슬러 올라가 통로 밖이거나 lengthM보다 멀었던 때부터
  들어갈 때까지)에서 그 물체가 통로 안 가장 가까운 정답일 때 처음 STOP이 난 블록의 거리 ÷ 다가오는 속도. 없으면 놓침
- ② 정지 STOP: 정지 물체가 STOP 구간에 1 s 이상 있었으면, 그 접근에서 처음 STOP 때 거리 ≥ 0.9 × stopM이어야 한다(§9.2)
- STOP 구간 진입이 정답 띠(클릭 검증 p95) 안에서만 일어나면(통로를 띠만큼 좁히고 stopM − 띠보다 깊이 들어오지 않음) 판정 불가
- ③ 오경보: 경고 시작 블록이 (가) 통로 안 정답 거리 ≤ warnMaxM + hysteresisM + 0.3 m도, (나) 어느 물체의 STOP 구간 진입 3 s 전 ~ 나갈 때도 아님
- ④ 회전: |앱 진행 방향 − 정답 진행 방향| > 5°인 걷는 블록 비율, 걷기 시작·돌아선 뒤 TRANS_S 안(전환)과 그 밖(정상)
- 정답은 소리를 낸 블록 시각(`tBlockNs`)에서 읽는다(M48, ①~③). ④는 진행 방향 추정 오차라 블록이 쓴 자세 시각 그대로
- 정답 없는 경고 시간(M48, 보고): 경고 블록 중 통로 안 정답이 경고 거리 안에 없는 시간
더한 단계가 `check`인 물체(같은 점을 여러 거리에서 누른 클릭 검증 전용)는 검증에만 쓰고 채점에서 뺀다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from metrics import ACTIVE, MATCH_MARGIN_M, WALKING_MPS, check_complete, load_config, load_frames, p

EVENTS = "annotations/events.json"
HALF_WIN_S = 0.5  # 정답 진행 방향·속도 창(앞뒤)
MOVE_MIN_M = 0.25  # 창 안 이동이 이보다 작으면 카메라 정면을 진행 방향으로
VEL_PAST_S = 1.0  # 움직이는 물체 속도: 지난 이 창의 클릭 직선 맞춤(주석: 4 m 안 0.3 s마다 → 3~4점)
GAP_MAX_S = 1.5  # 이보다 가까운 두 클릭 사이만 잇는다
EXTRAP_S = 0.5  # 첫·마지막 클릭 밖으로 늘이는 한도
LINE_STEP_M = 0.05
PRE_STOP_S = 3.0  # (나) STOP 구간 진입 전 정답 경고 구간(Tourki 외 2025)
STOP_RATIO = 0.9  # ② 첫 STOP 거리 하한(§9.2)
ZONE_MIN_S = 1.0  # ② STOP 구간에 이만큼 있어야 판정
TTC_MIN_S = 1.0  # ① 기준(프로포절: stopM 1.0 m ÷ 보행 1 m/s)
HEADING_TOL_DEG = 5.0  # ④(M42)
TRANS_S = 2.0
TURN_DEG, TURN_WIN_S = 30.0, 1.0  # 돌아섬: 정답 진행 방향이 TURN_WIN_S 안에 TURN_DEG 넘게 바뀜(자체 선택)
PAIR_MAX_M = 3.5  # 클릭 검증 짝: 발이 이 안(발 클릭 범위 1.9~3.5 m)
CLICK_TOL_M = 0.2  # 클릭 검증 목표 p95(자체 선택: 1.3 m/s에서 TTC 띠 0.15 s)
# 움직이는 물체 앞면 = 클릭 점 − 이 값(M52 P2: D_a 안 원시 깊이 몸통 화소 − 클릭 점의 중앙값을 뒤집은 값, T 접근 3세션 합친 0.149 m
# [0.112, 0.207]). 이전에는 반지름 0.2 m(M47 자체 선택)를 썼다. 옆 방향(통로 안인지)은 반지름 그대로: 몸 앞뒤 두께와 어깨 폭이 다르다
FRONT_OFFSET_M = 0.15
UNEXPL_GAP_S = 0.2  # 정답 없는 경고 구간을 이을 때 이보다 짧은 틈은 같은 구간(보고용 묶음)


def load_events(session: Path) -> dict | None:
    f = session / EVENTS
    return json.loads(f.read_text(encoding="utf-8")) if f.is_file() else None


def clicks_of(o: dict) -> list[dict]:
    return [c for c in o.get("clicks", []) if c.get("pointW") is not None]


def validation(ev: dict, frames: pd.DataFrame | None = None) -> dict:
    """정지 점 물체를 여러 거리에서 누른 위치의 흩어짐(중앙값에서), 같은 장 발·허리 클릭의 차(수평 m). [frames]가 있으면 발이 카메라에서
    PAIR_MAX_M 안인 짝만 쓴다(발 클릭을 쓰는 범위, §9.5: 4 m 짝은 화소·흔들림 오차가 0.2 m를 넘어 띠를 부풀렸다)."""
    spread, pair = [], []
    for o in ev["objects"]:
        cs = clicks_of(o)
        if o["kind"] == "static" and o.get("shape", "point") == "point" and len(cs) >= 2:
            xz = np.array([[c["pointW"][0], c["pointW"][2]] for c in cs])
            spread += np.linalg.norm(xz - np.median(xz, axis=0), axis=1).tolist()
        if o["kind"] == "moving":
            by_t: dict[int, dict] = {}
            for c in cs:
                by_t.setdefault(c["tNs"], {})[c["plane"]] = c["pointW"]
            for tn, v in by_t.items():
                if len(v) != 2:
                    continue
                if frames is not None:
                    cx, cz = np.interp(tn, frames.tNs, frames.tx), np.interp(tn, frames.tNs, frames.tz)
                    if np.hypot(v["FEET"][0] - cx, v["FEET"][2] - cz) > PAIR_MAX_M:
                        continue
                pair.append(float(np.hypot(v["FEET"][0] - v["HEAD"][0], v["FEET"][2] - v["HEAD"][2])))
    allv = spread + pair
    floor_from: dict[str, int] = {}
    for o in ev["objects"]:
        for c in o.get("clicks", []):
            floor_from[c.get("floorFrom", "rgb")] = floor_from.get(c.get("floorFrom", "rgb"), 0) + 1
    return {"staticSpreadM": {"p95": p(spread, 95), "n": len(spread)}, "feetHeadM": {"p95": p(pair, 95), "n": len(pair)},
            "p95M": p(allv, 95), "n": len(allv), "targetM": CLICK_TOL_M,
            "met": None if not allv else bool(p(allv, 95) <= CLICK_TOL_M), "floorFrom": floor_from}


def moving_track(o: dict):
    """움직이는 물체: 위치는 클릭 사이 선형 보간(간격 ≤ GAP_MAX_S, 끝 밖으로 EXTRAP_S까지), 속도는 지난 VEL_PAST_S 클릭의 직선 맞춤 기울기.
    앞뒤 창 맞춤은 멈추기 직전(STOP을 판정하는 때)에 멈춘 뒤 클릭이 섞여 위치·속도가 끌려 지난 창만 쓴다(멈추며 느려지는 중이면 속도가
    크게 나와 TTC가 짧게, 곧 안전 쪽으로 틀린다). 같은 장의 발·머리 끝 클릭은 평균한다."""
    by_t: dict[int, list] = {}
    for c in clicks_of(o):
        by_t.setdefault(c["tNs"], []).append([c["pointW"][0], c["pointW"][2]])
    t = np.array(sorted(by_t)) / 1e9
    xz = np.array([np.mean(by_t[k], axis=0) for k in sorted(by_t)]) if by_t else np.zeros((0, 2))

    def vel(ts: float):
        w = (t >= ts - VEL_PAST_S) & (t <= ts + 1e-6)
        if w.sum() >= 2 and np.ptp(t[w]) > 0.05:
            return np.polyfit(t[w], xz[w], 1)[0]
        return np.array([np.nan, np.nan])

    def at(ts: float):
        """시각 [ts](s)의 (위치 xz, 속도 xz 또는 nan) 또는 None(정답 없음)."""
        if t.size == 0:
            return None
        i = np.searchsorted(t, ts, side="right")
        if 0 < i < t.size and t[i] - t[i - 1] <= GAP_MAX_S:
            pos = xz[i - 1] + (xz[i] - xz[i - 1]) * ((ts - t[i - 1]) / (t[i] - t[i - 1]))
        elif i == t.size and ts - t[-1] <= EXTRAP_S:
            v = vel(t[-1])
            pos = xz[-1] + (np.nan_to_num(v) * (ts - t[-1]))
        elif i == 0 and t[0] - ts <= EXTRAP_S:
            pos = xz[0]
        else:
            return None
        return pos, vel(min(ts, t[-1]))
    return at


def line_samples(o: dict) -> np.ndarray:
    pts = np.array([[c["pointW"][0], c["pointW"][2]] for c in clicks_of(o)])
    if o.get("shape", "point") == "point" or len(pts) < 2:
        return np.median(pts, axis=0)[None, :] if len(pts) else np.zeros((0, 2))
    out = []
    for a, b in zip(pts[:-1], pts[1:]):
        n = max(1, int(np.ceil(np.linalg.norm(b - a) / LINE_STEP_M)))
        out += [a + (b - a) * s for s in np.linspace(0, 1, n + 1)]
    return np.array(out)


def near_along(pts: np.ndarray, radius: float, head: np.ndarray, f: np.ndarray, c: dict, front: float | None = None) -> float | None:
    """점들 중 통로 안(머리 [head], 진행 방향 [f]) 가장 가까운 진행 방향 거리(`metrics.truth_nearest`와 같은 규칙). 통로 밖이면 None.
    앞면은 진행 방향 거리 − [front](없으면 [radius]), 옆 방향은 [radius]."""
    if len(pts) == 0:
        return None
    rel = pts - head
    along = rel @ f
    lat = rel @ np.array([-f[1], f[0]])
    near = np.maximum(along - (radius if front is None else front), -c["behindM"])
    ok = (np.abs(lat) - radius <= c["widthM"] / 2) & (near <= along + radius) & (near <= c["lengthM"])
    return float(near[ok].min()) if ok.any() else None


def wrap(a):
    return (np.asarray(a) + 180.0) % 360.0 - 180.0


def compute(session: Path, run_dir: Path) -> dict:
    check_complete(run_dir)
    ev = load_events(session)
    if ev is None:
        raise FileNotFoundError(f"{session / EVENTS} 없음: viewer 클릭 모드로 정답을 먼저 만든다")
    cfg = load_config(run_dir)
    pol, cor = cfg["policy"], cfg["corridor"]
    ox, _, oz = cfg["head"]["offsetFromCameraM"]
    frames = load_frames(session)
    g = pd.read_csv(run_dir / "guidance.csv")
    blocks = g.drop_duplicates("tBlockNs").reset_index(drop=True)

    ft = frames.tNs.to_numpy() / 1e9
    fx_, fz_ = frames.tx.to_numpy(), frames.tz.to_numpy()
    qx, qy, qz, qw = (frames[k].to_numpy() for k in ("qx", "qy", "qz", "qw"))
    fwd = np.stack([-2 * (qx * qz + qw * qy), -(1 - 2 * (qx * qx + qy * qy))], axis=1)  # GL 카메라 −Z의 수평 성분
    fwd /= np.maximum(np.linalg.norm(fwd, axis=1, keepdims=True), 1e-9)

    def cam(ts):
        return np.array([np.interp(ts, ft, fx_), np.interp(ts, ft, fz_)])

    def truth_dirs(times):
        """시각마다 정답 진행 방향·머리·머리 속도·걷는 방향 여부."""
        m = len(times)
        hd, fd, vh, wd = np.zeros((m, 2)), np.zeros((m, 2)), np.zeros((m, 2)), np.full(m, False)
        for k, ts in enumerate(times):
            d = cam(ts + HALF_WIN_S) - cam(ts - HALF_WIN_S)
            vh[k] = d / (2 * HALF_WIN_S)
            if np.linalg.norm(d) > MOVE_MIN_M:
                f = d / np.linalg.norm(d)
                wd[k] = True
            else:
                f = fwd[min(np.searchsorted(ft, ts), len(ft) - 1)]
            fd[k] = f
            hd[k] = cam(ts) + np.array([-f[1], f[0]]) * ox + f * oz
        return hd, fd, vh, wd

    # 정답은 소리를 낸 블록 시각에서 읽는다(M48: 블록은 그 블록이 쓴 자세보다 약 170 ms 뒤라 자세 시각으로는 정보 나이 몫을 못 봄).
    # ④는 진행 방향 추정 오차를 보므로 자세 시각 그대로(tp).
    t = blocks.tBlockNs.to_numpy() / 1e9
    tp = blocks.poseTNs.to_numpy() / 1e9
    n = len(t)
    head, fdir, vhead, _ = truth_dirs(t)

    objs = [o for o in ev["objects"] if o.get("added") != "check"]  # 검증 전용 물체는 채점에서 뺀다
    tracks = [moving_track(o) if o["kind"] == "moving" else None for o in objs]
    statics = [None if o["kind"] == "moving" else line_samples(o) for o in objs]
    val = validation(ev, frames)
    tol = val["p95M"] if val["p95M"] is not None else CLICK_TOL_M
    # 정답 띠: 통로를 양쪽으로 tol만큼 좁힌 통로(A_in)로도 STOP 구간 깊이 tol 넘게 들어와야 확실한 진입이다
    cor_in = {**cor, "widthM": max(cor["widthM"] - 2 * tol, 0.0)}
    A = np.full((n, len(objs)), np.nan)  # 통로 안 진행 방향 거리
    A_in = np.full((n, len(objs)), np.nan)
    V = np.full((n, len(objs), 2), np.nan)  # 물체 속도(움직이는 물체)
    for k in range(n):
        for j, o in enumerate(objs):
            if tracks[j] is not None:
                r = tracks[j](t[k])
                if r is None:
                    continue
                pos, vel = r
                pts, rad, front = np.asarray(pos)[None, :], float(o.get("radiusM", 0.0)), FRONT_OFFSET_M
                V[k, j] = vel
            else:
                pts, rad, front = statics[j], 0.0, None
            a = near_along(pts, rad, head[k], fdir[k], cor, front)
            if a is not None:
                A[k, j] = a
            a = near_along(pts, rad, head[k], fdir[k], cor_in, front)
            if a is not None:
                A_in[k, j] = a

    band = blocks.band.fillna("").to_numpy()
    active = blocks.state.isin(ACTIVE).to_numpy()
    with np.errstate(all="ignore"):
        nearest = np.where(np.isnan(A).all(axis=1), -1, np.nanargmin(np.where(np.isnan(A), np.inf, A), axis=1))
    stop_m = pol["stopM"]

    # STOP 구간 진입 사건(물체마다)
    entries, events = [], []
    for j, o in enumerate(objs):
        zone = A[:, j] < stop_m
        k = 0
        while k < n:
            if not zone[k]:
                k += 1
                continue
            k0 = k
            while k + 1 < n and zone[k + 1]:
                k += 1
            k1 = k
            s = k0
            while s - 1 >= 0 and not np.isnan(A[s - 1, j]) and A[s - 1, j] <= cor["lengthM"]:
                s -= 1
            entries.append((t[k0], t[k1]))
            moving = o["kind"] == "moving"
            hi = k0 if moving else k1
            ks = next((q for q in range(s, hi + 1) if band[q] == "STOP" and active[q] and nearest[q] == j), None)
            e = {"object": o["id"], "kind": o["kind"], "label": o.get("label", ""), "situation": o.get("situation"),
                 "entryS": round(t[k0] - t[0], 3), "exitS": round(t[k1] - t[0], 3)}
            if ks is not None:
                e["stopS"] = round(t[ks] - t[0], 3)
                e["stopAlongM"] = round(float(A[ks, j]), 3)
            # 첫 경고(WARN·STOP) 거리(M54 설계 표 8 W 보고 항목): STOP과 같은 창·조건
            kw = next((q for q in range(s, hi + 1) if band[q] in ("WARN", "STOP") and active[q] and nearest[q] == j), None)
            if kw is not None:
                e["warnS"] = round(t[kw] - t[0], 3)
                e["warnAlongM"] = round(float(A[kw, j]), 3)
            with np.errstate(all="ignore"):
                deep = np.nanmin(np.r_[A_in[k0:k1 + 1, j], np.inf]) < stop_m - tol
            e["minAlongM"] = round(float(np.nanmin(A[k0:k1 + 1, j])), 3)
            ttc = band_s = None
            if moving and ks is not None:
                closing = float(-((V[ks, j] - vhead[ks]) @ fdir[ks]))
                if np.isfinite(closing):
                    ttc = A[ks, j] / closing if closing > 0.05 else float("inf")
                    band_s = tol / closing if closing > 0.05 else 0.0
                    e["closingMps"] = round(closing, 3)
                    e["ttcS"] = round(float(ttc), 3) if np.isfinite(ttc) else None
                    e["ttcBandS"] = round(band_s, 3)
            if moving and ks is None:
                # 들어온 뒤에야 난 STOP(보고): 놓침과 늦은 STOP을 가른다
                kl = next((q for q in range(k0, k1 + 1) if band[q] == "STOP" and active[q] and nearest[q] == j), None)
                if kl is not None:
                    e["lateStopS"] = round(t[kl] - t[0], 3)
                    e["lateStopAlongM"] = round(float(A[kl, j]), 3)
            if not deep:
                e["verdict"] = "undecided"  # 정답 띠 안에서만 STOP 구간에 닿음(가장자리 스침)
            elif moving:
                if ks is None:
                    e["verdict"] = "missed"
                elif ttc is None:
                    e["verdict"] = "noVelocity"
                else:
                    e["verdict"] = "undecided" if abs(ttc - TTC_MIN_S) <= band_s else ("ok" if ttc >= TTC_MIN_S else "late")
            else:
                if t[k1] - t[k0] + (t[1] - t[0] if n > 1 else 0) < ZONE_MIN_S:
                    e["verdict"] = "short"
                elif ks is None:
                    e["verdict"] = "missed"
                else:
                    first = A[ks, j]
                    e["verdict"] = "undecided" if abs(first - STOP_RATIO * stop_m) <= tol else ("ok" if first >= STOP_RATIO * stop_m else "late")
            events.append(e)
            k += 1

    # ③ 오경보
    far = pol["warnMaxM"] + pol["hysteresisM"] + MATCH_MARGIN_M
    onsets, fa_t = 0, []
    prev = False
    for k in range(n):
        warned = band[k] in ("WARN", "STOP")
        if warned and active[k] and not prev:
            onsets += 1
            near = np.nanmin(A[k]) if not np.isnan(A[k]).all() else np.inf
            legit = near <= far or any(a - PRE_STOP_S <= t[k] <= b for a, b in entries)
            if not legit:
                fa_t.append(round(t[k] - t[0], 3))
        prev = warned
    dur_min = (t[-1] - t[0]) / 60 if n > 1 else 0.0

    # 정답 없는 경고 시간(M48): 경고 블록 중 통로 안 정답이 경고 거리(far) 안에 없는 시간. ③은 시작만 세어 물체가 떠난 뒤 이어지는 경고를 못 봄
    dt = np.diff(t, append=t[-1] + (t[-1] - t[-2] if n > 1 else 0.0))
    with np.errstate(all="ignore"):
        near_all = np.where(np.isnan(A).all(axis=1), np.inf, np.nanmin(np.where(np.isnan(A), np.inf, A), axis=1))
    unexpl = active & np.isin(band, ("WARN", "STOP")) & (near_all > far)
    segs = []  # 틈이 UNEXPL_GAP_S보다 짧은 블록끼리 한 구간
    for k in np.flatnonzero(unexpl):
        if segs and t[k] - segs[-1]["_end"] < UNEXPL_GAP_S:
            g = segs[-1]
        else:
            g = {"startS": round(t[k] - t[0], 3), "durS": 0.0, "stop": False}
            segs.append(g)
        g["_end"] = t[k]
        g["durS"] += float(dt[k])
        g["stop"] = g["stop"] or band[k] == "STOP"
    for g in segs:
        g.pop("_end")
        g["durS"] = round(g["durS"], 3)

    # ④ 회전
    _, fdir_p, _, walk_dir = truth_dirs(tp)
    app = blocks.headingDeg.to_numpy(dtype=float)
    truth_deg = np.degrees(np.arctan2(fdir_p[:, 0], -fdir_p[:, 1]))  # 월드 −Z에서 +X 쪽(core Heading 규약)
    err = wrap(app - truth_deg)
    ref = np.maximum(np.searchsorted(tp, tp - 1.0, side="right") - 1, 0)
    speed = np.array([np.linalg.norm(cam(tp[k]) - cam(tp[ref[k]])) / max(tp[k] - tp[ref[k]], 1e-3) for k in range(n)])
    walking = (speed > WALKING_MPS) & ~np.isnan(app) & walk_dir
    tw = np.maximum(np.searchsorted(tp, tp - TURN_WIN_S, side="right") - 1, 0)
    turned = np.abs(wrap(truth_deg - truth_deg[tw])) > TURN_DEG
    last, since = -1e9, np.empty(n)
    for k in range(n):
        if k > 0 and ((walking[k] and not walking[k - 1]) or (walking[k] and turned[k])):
            last = tp[k]
        since[k] = tp[k] - last
    trans = walking & (since < TRANS_S)
    steady = walking & (since >= TRANS_S)

    def frac(m):
        return {"over5": float((np.abs(err[m]) > HEADING_TOL_DEG).mean()) if m.any() else None, "n": int(m.sum())}

    return {
        "validation": val,
        "events": events,
        "approach": {"n": sum(e["kind"] == "moving" for e in events),
                     "verdicts": {v: sum(e.get("verdict") == v for e in events if e["kind"] == "moving") for v in ("ok", "late", "missed", "undecided", "noVelocity")}},
        "staticStop": {"n": sum(e["kind"] != "moving" and e["verdict"] != "short" for e in events),
                       "missed": sum(e["kind"] != "moving" and e["verdict"] in ("missed", "late") for e in events)},
        "falseAlarm": {"onsets": onsets, "false": len(fa_t), "perMin": len(fa_t) / dur_min if dur_min > 0 else None,
                       "falseOnsetS": fa_t, "durationMin": round(dur_min, 3)},
        "unexplainedWarn": {"s": round(float(dt[unexpl].sum()), 3), "stopS": round(float(dt[unexpl & (band == "STOP")].sum()), 3), "segments": segs},
        "heading": {"transition": frac(trans), "steady": frac(steady)},
    }


def main(args: list[str]) -> None:
    if not args:
        print(__doc__)
        sys.exit(2)
    session = Path(args[0])
    if len(args) == 1:
        ev = load_events(session)
        if ev is None:
            sys.exit(f"{session / EVENTS} 없음")
        print(json.dumps(validation(ev), ensure_ascii=False, indent=2))
        return
    run_dir = Path(args[1])
    res = compute(session, run_dir)
    (run_dir / "event_eval.json").write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding="utf-8")
    v, fa = res["validation"], res["falseAlarm"]
    print(f"클릭 검증 p95 {v['p95M']} m (n {v['n']}, 목표 ≤ {CLICK_TOL_M}), 바닥면 {v['floorFrom']}")
    for e in res["events"]:
        print(f"  {e['kind']:6s} {e['object']:4s} 진입 {e['entryS']:7.2f} s  STOP {e.get('stopS', '-')}  거리 {e.get('stopAlongM', '-')}  "
              f"TTC {e.get('ttcS', '-')}±{e.get('ttcBandS', '-')}  → {e['verdict']}")
    print(f"오경보 {fa['false']}/{fa['onsets']} 시작, 분당 {fa['perMin']}, 시각 {fa['falseOnsetS']}")
    print(f"회전 5° 넘는 블록: 전환 {res['heading']['transition']}, 정상 {res['heading']['steady']}")
    print(f"정답 없는 경고 {res['unexplainedWarn']['s']} s(STOP {res['unexplainedWarn']['stopS']} s), 구간 {res['unexplainedWarn']['segments']}")


if __name__ == "__main__":
    main(sys.argv[1:])
