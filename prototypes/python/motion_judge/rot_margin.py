"""M57 A-0 보정 시험: 위반 여유를 거리 구간 × 회전 속도 구간으로(재생 없음, stage0.py와 같은 자료·정의).

    python rot_margin.py sigma <재생 폴더>... --out <json>
    python rot_margin.py viol  <재생 폴더>... --table <json> --zlag <stage0 sigma.json> --out <csv>

A-0 결과: 정지 원시 점 깊이 차의 꼬리는 두 장 사이 회전 속도를 따라 커졌다(approach_shift.py). 그래서 여유 = (Z 구간, 되돌아볼 장 수,
회전 구간)마다 정지 깊이 차의 묶음(실내·O01·T)별 97.7% 분위수의 최댓값(0-2와 같은 규칙, 회전 구간마다 정지 위반 ≤ 2.3%).
되돌아볼 장 수는 회전과 무관하게 stage0의 Z 구간 표(--zlag)를 그대로 쓰고, 여유만 그 쌍의 회전 구간 값으로 바꾼다.
출력 csv는 stage0 viol과 같은 열이라 judge_l.py가 그대로 읽는다.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

import stage0 as S
from free_space import compare

ROT_EDGES = np.array([0.0, 2.0, 5.0, 10.0, 1e9])  # °/s, A-0 구간
NR = len(ROT_EDGES) - 1


def rot_bin(a, b) -> int:
    r = S.rot_deg_s(a, b)
    return int(np.clip(np.digitize(r, ROT_EDGES) - 1, 0, NR - 1)) if np.isfinite(r) else 0


def sigma(runs: list[Path], out: Path) -> None:
    acc: dict = {}
    for run in runs:
        session, grp, cfg = S.session_of(run), S.group_of(run), S.load_config(run)
        meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
        fr = S.load_frames(session).reset_index(drop=True)
        sp = pd.read_csv(run / "slow_path.csv")
        frames = S.raw_frames(session, meta, fr)
        mv = S.movers(session)
        for i, (f, _) in enumerate(frames):
            fy = S.floor_at(sp, f.t_capture_ns)
            if fy is None:
                continue
            p, _ = S.points_of(f, 30)
            keep = S.obstacle_points(p, f.cam_w, fy, cfg)
            if mv:
                keep &= ~S._near_person(p[:, [0, 2]], mv, f.t_capture_ns / 1e9)
            p = p[keep][::2]
            for lag in S.LAGS:
                if i - lag < 0:
                    continue
                past = frames[i - lag][0]
                rb = rot_bin(past, f)
                ok, diff, z = compare(p, past, 30)
                zb = np.digitize(z[ok], S.Z_EDGES) - 1
                for b in np.unique(zb):
                    if 0 <= b < len(S.Z_EDGES) - 1:
                        acc.setdefault((grp, lag, int(b), rb), []).append(diff[ok][zb == b].astype(np.float32))
        print(f"{run.name} {grp} raw frames {len(frames)}", file=sys.stderr)
    res = []
    for (grp, lag, b, rb), parts in sorted(acc.items()):
        d = np.concatenate(parts)
        res.append(dict(group=grp, lag=lag, zC=float((S.Z_EDGES[b] + S.Z_EDGES[b + 1]) / 2), rotBin=rb, n=int(len(d)),
                        q977=float(np.quantile(d, 0.977)), median=float(np.median(d))))
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


def margin_rot(path: Path, lag_b: np.ndarray, min_n: int = 2000) -> np.ndarray:
    """(Z 구간, 회전 구간) 여유: 그 Z 구간의 되돌아볼 장 수에서 묶음별 97.7% 분위수의 최댓값. 점이 min_n 미만인 묶음은 빼고,
    한 묶음도 없으면 바로 아래 회전 구간 값(회전이 클수록 여유가 줄지 않게 누적 최댓값)."""
    r = json.loads(path.read_text(encoding="utf-8"))
    nb = len(S.Z_EDGES) - 1
    m = np.full((nb, NR), np.nan)
    for b in range(nb):
        zc = float((S.Z_EDGES[b] + S.Z_EDGES[b + 1]) / 2)
        for rb in range(NR):
            xs = [x["q977"] for x in r if x["group"] in ("indoor", "o01", "t") and x["lag"] == lag_b[b]
                  and abs(x["zC"] - zc) < 1e-6 and x["rotBin"] == rb and x["n"] >= min_n]
            if xs:
                m[b, rb] = max(xs)
        for rb in range(NR):  # 누적 최댓값(빈 칸은 아래 구간 값)
            prev = m[b, rb - 1] if rb > 0 else np.nan
            m[b, rb] = np.nanmax([m[b, rb], prev]) if np.isfinite([m[b, rb], prev]).any() else np.nan
    return m


def viol(runs: list[Path], out: Path, table: Path, zlag: Path, v_min: float, dt_s: float) -> None:
    lag_b, _, _ = S.margin_table(zlag, v_min, dt_s)
    m_rb = margin_rot(table, lag_b)
    print("lag by Z", lag_b.tolist(), "\nmargin [Z][rot]\n", np.round(m_rb, 3), file=sys.stderr)
    rows = []
    for run in runs:
        session, grp, cfg = S.session_of(run), S.group_of(run), S.load_config(run)
        margin = cfg["track"]["matchRadiusM"]
        meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
        fr = S.load_frames(session).reset_index(drop=True)
        sp = pd.read_csv(run / "slow_path.csv")
        ob = pd.read_csv(run / "obstacles.csv")
        ob_t = np.sort(ob.tCaptureNs.unique())
        frames = S.raw_frames(session, meta, fr)
        caps = np.array([f.t_capture_ns for f, _ in frames])
        mv = S.movers(session)
        L = S._Labels(session, fr, meta) if (session / "semantic" / "index.csv").is_file() else None
        for i, (f, arrive) in enumerate(frames):
            fy = S.floor_at(sp, f.t_capture_ns)
            if fy is None:
                continue
            p, zc, pconf = S.points_of(f, 30, with_conf=True)
            keep = S.obstacle_points(p, f.cam_w, fy, cfg)
            p, zc, pconf = p[keep], zc[keep], pconf[keep]
            if len(p) == 0:
                continue
            zb = np.clip(np.digitize(zc, S.Z_EDGES) - 1, 0, len(S.Z_EDGES) - 2)
            tgt = f.t_capture_ns - (lag_b[zb] * dt_s * 1e9).astype(np.int64)
            back = np.searchsorted(caps[:i], tgt, side="right") - 1
            cmp_ok = np.zeros(len(p), dtype=bool)
            vio = np.zeros(len(p), dtype=bool)
            for j in np.unique(back):
                if j < 0:
                    continue
                sel = back == j
                rb = rot_bin(frames[j][0], f)
                ok, diff, _ = compare(p[sel], frames[j][0], 30)
                cmp_ok[sel] = ok
                vio[sel] = ok & (diff > m_rb[zb[sel], rb])
            lab = np.full(len(p), -1)
            if L is not None:
                got = L.at(arrive)
                if got is not None and (arrive - got[0]) <= cfg["vision"]["maxLabelAgeMs"] * 1e6:
                    lab = S._Labels.read(got[1], p)
            rot = S.rot_deg_s(frames[i - 1][0], f) if i > 0 else np.nan
            t_s = f.t_capture_ns / 1e9
            gates, truth = [], []
            for oid, tr, rad, pfy in mv:
                r = tr(t_s)
                if r is None:
                    continue
                pos, vel = r
                truth.append(pos)
                sel = (np.hypot(p[:, 0] - pos[0], p[:, 2] - pos[1]) <= rad + margin) & (p[:, 1] < pfy + S.PERSON_TOP_M)
                hd = float(np.hypot(pos[0] - f.cam_w[0], pos[1] - f.cam_w[2]))
                spd = float(np.hypot(*vel)) if np.all(np.isfinite(vel)) else np.nan
                gates.append(("person", oid, sel, dict(speed=spd, hd=hd)))
            k = int(np.searchsorted(ob_t, arrive, side="right")) - 1
            if k >= 0:
                for o in ob[ob.tCaptureNs == ob_t[k]].itertuples(index=False):
                    lo = np.array([o.aabbMinX, o.aabbMinY, o.aabbMinZ]) - margin
                    hi = np.array([o.aabbMaxX, o.aabbMaxY, o.aabbMaxZ]) + margin
                    sel = np.all((p >= lo) & (p <= hi), axis=1)
                    cx, cz = (o.aabbMinX + o.aabbMaxX) / 2, (o.aabbMinZ + o.aabbMaxZ) / 2
                    near = any(np.hypot(cx - q[0], cz - q[1]) <= S.NEAR_PERSON_M for q in truth)
                    gates.append(("near_person" if near else "static", int(o.id), sel, {}))
            for kind, gid, sel, extra in gates:
                n = int(sel.sum())
                if n == 0:
                    continue
                lc = np.bincount(lab[sel][lab[sel] >= 0], minlength=12)[:12]
                rows.append(dict(group=grp, session=session.name, tRawNs=f.t_capture_ns, kind=kind, gate=gid, nPts=n,
                                 nCmp=int(cmp_ok[sel].sum()), nViol=int(vio[sel].sum()), zMed=float(np.median(zc[sel])),
                                 rotDegS=rot, **{f"lab{c}": int(lc[c]) for c in range(12)}, **extra))
        print(f"{run.name} {grp} raw frames {len(frames)} rows {len(rows)}", file=sys.stderr)
    out.parent.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(rows).to_csv(out, index=False)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["sigma", "viol"])
    ap.add_argument("runs", nargs="+", type=Path)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--table", type=Path)
    ap.add_argument("--zlag", type=Path)
    ap.add_argument("--vmin", type=float, default=0.17)
    ap.add_argument("--dt", type=float, default=0.1)
    a = ap.parse_args()
    if a.cmd == "sigma":
        sigma(a.runs, a.out)
    else:
        viol(a.runs, a.out, a.table, a.zlag, a.vmin, a.dt)


if __name__ == "__main__":
    main()
