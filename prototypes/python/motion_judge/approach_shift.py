"""M57 A-0: 다가갈 때 정지 면이 밀리는 원인 가르기(재생 없음, B+W5 재생 출력 + 세션 원시 깊이·자세).

    python approach_shift.py <재생 폴더>... --out <csv>

원시 장 i와 lag장 앞 장 쌍마다 정지 원시 점(1~3 m, 바닥 위, T는 움직이는 정답 1 m 밖)의 깊이 차 중앙값(지난 깊이 − 지금 점의 그 카메라
깊이, 0단계 0-1과 같은 정의)과, 두 카메라 사이 이동의 시선 성분(점 쪽으로 다가간 거리), 회전 각을 적는다. 자세는 core와 같은 짝(도착 이전
행 중 촬영 시각에 가장 가까운 행)에서 행을 k만큼 옮긴 것도 함께 잰다(분석 전용: 일정한 자세–깊이 시각 차 가설).

가설별 예측: (1) 깊이·자세 축척 불일치 → 깊이 차 = (s − 1)·다가간 거리, 기울기가 lag와 무관. (2) 원시 깊이 내용이 장마다 다르게 묵음 →
깊이 차가 속도 × 묵은 정도 차로 묶여, lag가 늘면 기울기가 줄어듦. (3) 일정한 시각 차 → 등속 이동에서는 상쇄되고 회전과 함께 커짐, k를 옮기면 줄어듦.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from PIL import Image

import stage0 as S
from free_space import RawFrame, compare

LAGS = (1, 3, 6)
SHIFTS = (-2, -1, 0, 1, 2)
Z_LO, Z_HI = 1.0, 3.0


def frames_with_rows(session: Path, meta: dict, fr: pd.DataFrame):
    """원시 장마다 (깊이, 신뢰도, 내부 파라미터, 촬영 시각, 짝지은 자세 행 번호) — stage0.raw_frames와 같은 짝."""
    ft = fr.tNs.to_numpy()
    out, last = [], -1
    for i, row in enumerate(fr.itertuples(index=False)):
        if not isinstance(row.rawDepthFile, str) or row.tracking != "TRACKING" or row.rawDepthTNs <= last:
            continue
        last = int(row.rawDepthTNs)
        j = int(np.argmin(np.abs(ft[:i + 1] - last)))
        d = S.load_depth_m(session / row.rawDepthFile)
        conf = np.array(Image.open(session / row.confFile)) if isinstance(row.confFile, str) else np.full(d.shape, 255, np.uint8)
        out.append((d, conf, S.depth_k(meta, d.shape[1], d.shape[0]), last, j))
    return out


def pose_of(fr: pd.DataFrame, j: int):
    j = min(max(j, 0), len(fr) - 1)
    pr = fr.iloc[j]
    if pr.tracking != "TRACKING":
        return None
    rot = S.quat_to_rot(np.array([[pr.qx, pr.qy, pr.qz, pr.qw]]))[0] @ S.FLIP
    return rot, np.array([pr.tx, pr.ty, pr.tz])


def run_one(run: Path, rows: list) -> None:
    session, grp, cfg = S.session_of(run), S.group_of(run), S.load_config(run)
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    fr = S.load_frames(session).reset_index(drop=True)
    sp = pd.read_csv(run / "slow_path.csv")
    mv = S.movers(session)
    fs = frames_with_rows(session, meta, fr)
    for i, (d, conf, k, t, j) in enumerate(fs):
        fy = S.floor_at(sp, t)
        if fy is None:
            continue
        for sh in SHIFTS:
            pn = pose_of(fr, j + sh)
            if pn is None:
                continue
            cur = RawFrame(t, d, conf, pn[0], pn[1], k["fx"], k["fy"], k["cx"], k["cy"])
            p, z = S.points_of(cur, 30)
            keep = S.obstacle_points(p, cur.cam_w, fy, cfg) & (z >= Z_LO) & (z <= Z_HI)
            if mv:
                keep &= ~S._near_person(p[:, [0, 2]], mv, t / 1e9)
            p = p[keep][::3]
            if len(p) < 50:
                continue
            u = p - cur.cam_w
            u = u / np.linalg.norm(u, axis=1, keepdims=True)
            u_mean = u.mean(axis=0)
            u_mean /= np.linalg.norm(u_mean)
            for lag in LAGS:
                if i - lag < 0:
                    continue
                d0, c0, k0, t0, j0 = fs[i - lag]
                pp = pose_of(fr, j0 + sh)
                if pp is None:
                    continue
                past = RawFrame(t0, d0, c0, pp[0], pp[1], k0["fx"], k0["fy"], k0["cx"], k0["cy"])
                ok, diff, _ = compare(p, past, 30)
                if ok.sum() < 50:
                    continue
                dd = diff[ok]
                approach = float((cur.cam_w - past.cam_w) @ u_mean)  # 점 쪽으로 다가간 거리(+)
                r = past.rot_w_from_c.T @ cur.rot_w_from_c
                rot = float(np.degrees(np.arccos(np.clip((np.trace(r) - 1) / 2, -1.0, 1.0))))
                rows.append(dict(group=grp, session=session.name, i=i, lag=lag, shift=sh, dt=(t - t0) / 1e9, n=int(ok.sum()),
                                 med=float(np.median(dd)), q25=float(np.quantile(dd, 0.25)), q75=float(np.quantile(dd, 0.75)),
                                 approach=approach, move=float(np.linalg.norm(cur.cam_w - past.cam_w)), rotDeg=rot))
    print(f"{run.name} {grp} raw {len(fs)} rows {len(rows)}", file=sys.stderr)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+", type=Path)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    rows: list = []
    for r in a.runs:
        run_one(r, rows)
    a.out.parent.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(rows).to_csv(a.out, index=False)


if __name__ == "__main__":
    main()
