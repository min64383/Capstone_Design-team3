"""같은 순간 원시 대 평활(사용자 승인 2026-11-07: 0-2 멈춤 뒤 고친 증거).

    python same_time.py sigma <재생 폴더>... --out <json>                 정지 창 점의 (평활 − 원시) 분포, 신뢰도·Z 구간별 분위수
    python same_time.py viol  <재생 폴더>... --table <json> --out <csv>   창별 원시 장마다 비교·어긋남 수

지난 원시 장 대신 같은 순간(±20 ms)의 평활 장과 비교한다. 평활은 움직이는 표면을 약 0.35 s 늦게 보여 주므로(M52·M54) 원시 점이
평활 표면보다 여유 넘게 앞에 있으면 "평활이 아직 따라오지 않은 곳" = 움직임 증거다. 장 사이 자세가 끼지 않는다.
경계 띠(frontend.labelBandPx 6, 장애물 역할 라벨 경계)는 원시 점에도 적용한다(평활의 막은 깊이 경계에 생김).
창·정답·바닥은 stage0.py와 같다.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

import stage0 as S
from free_space import RawFrame, compare

PAIR_MAX_NS = 20_000_000
BAND_PX = 6


def smoothed_frames(session: Path, meta: dict, fr: pd.DataFrame) -> tuple[np.ndarray, list]:
    """평활 장(처음 전달한 행 기준)의 촬영 시각과 RawFrame(신뢰도 = 255) 목록. 자세는 S.raw_frames와 같은 규칙."""
    ft = fr.tNs.to_numpy()
    out, caps, last = [], [], -1
    for i, row in enumerate(fr.itertuples(index=False)):
        if not isinstance(row.depthFile, str) or row.tracking != "TRACKING" or row.depthTNs <= last:
            continue
        last = int(row.depthTNs)
        j = int(np.argmin(np.abs(ft[:i + 1] - last)))
        pr = fr.iloc[j]
        rot = S.quat_to_rot(np.array([[pr.qx, pr.qy, pr.qz, pr.qw]]))[0] @ S.FLIP
        d = S.load_depth_m(session / row.depthFile)
        k = S.depth_k(meta, d.shape[1], d.shape[0])
        out.append(RawFrame(last, d, np.full(d.shape, 255, np.uint8), rot, np.array([pr.tx, pr.ty, pr.tz]), k["fx"], k["fy"], k["cx"], k["cy"]))
        caps.append(last)
    return np.array(caps), out


class Band:
    """라벨 영상마다 장애물 역할 경계까지 거리(깊이 화소, label_band.signed_k)를 기억하고, 월드 점이 띠 안(|k| ≤ BAND_PX)인지 낸다."""

    def __init__(self, L, depth_fx: float):
        from label_band import signed_k
        self.L, self.fx, self.signed_k, self.key, self.k = L, depth_fx, signed_k, None, None

    def inside(self, arrive_ns: int, max_age_ns: float, pts: np.ndarray) -> np.ndarray:
        if self.L is None:
            return np.zeros(len(pts), dtype=bool)
        got = self.L.at(arrive_ns)
        if got is None or arrive_ns - got[0] > max_age_ns:
            return np.zeros(len(pts), dtype=bool)
        img, k, rot, cam = got[1]
        if self.key != got[0]:
            self.key, self.k = got[0], self.signed_k(img.astype(np.int32), self.fx / k["fx"])
        pc = (pts - cam) @ rot
        ok = pc[:, 2] > 0.1
        zs = np.where(ok, pc[:, 2], 1.0)
        u = np.floor(k["fx"] * pc[:, 0] / zs + k["cx"]).astype(int)
        v = np.floor(k["fy"] * pc[:, 1] / zs + k["cy"]).astype(int)
        ok &= (u >= 0) & (v >= 0) & (u < k["width"]) & (v < k["height"])
        kk = np.full(len(pts), np.inf)
        kk[ok] = self.k[v[ok], u[ok]]
        return np.abs(kk) <= BAND_PX


def frames_of(run: Path):
    """재생 하나의 원시 장마다 (장 정보, 장애물 원시 점, 그 카메라 깊이, 신뢰도, 비교 여부, 평활 − 원시, 띠 안, 창들)."""
    session, grp, cfg = S.session_of(run), S.group_of(run), S.load_config(run)
    margin = cfg["track"]["matchRadiusM"]
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    fr = S.load_frames(session).reset_index(drop=True)
    sp = pd.read_csv(run / "slow_path.csv")
    ob = pd.read_csv(run / "obstacles.csv")
    ob_t = np.sort(ob.tCaptureNs.unique())
    frames = S.raw_frames(session, meta, fr)
    scaps, sfr = smoothed_frames(session, meta, fr)
    mv = S.movers(session)
    L = S._Labels(session, fr, meta) if (session / "semantic" / "index.csv").is_file() else None
    band = Band(L, frames[0][0].fx if frames else 1.0)
    max_age = cfg["vision"]["maxLabelAgeMs"] * 1e6
    for i, (f, arrive) in enumerate(frames):
        fy = S.floor_at(sp, f.t_capture_ns)
        if fy is None:
            continue
        j = int(np.argmin(np.abs(scaps - f.t_capture_ns)))
        if abs(int(scaps[j]) - f.t_capture_ns) > PAIR_MAX_NS:
            continue
        p, zc, pconf = S.points_of(f, 30, with_conf=True)
        keep = S.obstacle_points(p, f.cam_w, fy, cfg)
        p, zc, pconf = p[keep], zc[keep], pconf[keep]
        if len(p) == 0:
            continue
        ok, diff, _ = compare(p, sfr[j], 1)
        inband = band.inside(arrive, max_age, p)
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
        rot = S.rot_deg_s(frames[i - 1][0], f) if i > 0 else np.nan
        yield dict(group=grp, session=session.name, tRawNs=f.t_capture_ns, rotDegS=rot), p, zc, pconf, ok, diff, inband, gates


def sigma(runs: list[Path], out: Path) -> None:
    """정지 창 점(띠 밖)의 평활 − 원시를 신뢰도 구간(hi ≥ 128, lo 30~127)·Z 구간별로: 분위수 표. 띠 안 점은 따로(band) 센다."""
    acc: dict = {}
    for run in runs:
        for info, p, zc, pconf, ok, diff, inband, gates in frames_of(run):
            st = np.zeros(len(p), dtype=bool)
            for kind, _, sel, _ in gates:
                if kind == "static":
                    st |= sel
            for where, wm in (("out", ~inband), ("band", inband)):
                for cname, cm in (("hi", pconf >= 128), ("lo", pconf < 128)):
                    mm = st & ok & wm & cm
                    zb = np.digitize(zc[mm], S.Z_EDGES) - 1
                    for b in np.unique(zb):
                        if 0 <= b < len(S.Z_EDGES) - 1:
                            acc.setdefault((info["group"], where, cname, int(b)), []).append(diff[mm][zb == b].astype(np.float32))
        print(run.name, file=sys.stderr)
    for key in list(acc):
        acc.setdefault(("all",) + key[1:], []).extend(acc[key])
    res = []
    for (grp, where, cname, b), parts in sorted(acc.items()):
        d = np.concatenate(parts)
        res.append(dict(group=grp, where=where, conf=cname, zC=float((S.Z_EDGES[b] + S.Z_EDGES[b + 1]) / 2), n=int(len(d)),
                        median=float(np.median(d)), q977=float(np.quantile(d, 0.977)), q99=float(np.quantile(d, 0.99)),
                        q023=float(np.quantile(d, 0.023))))
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


def viol(runs: list[Path], out: Path, table: Path) -> None:
    """창별 원시 장마다 (점 수, 비교 수, 어긋남 수). 어긋남 = 평활 − 원시 > 여유(정지·띠 밖 묶음별 97.7% 분위수의 최댓값, 신뢰도·Z 구간별). 띠 밖 점만."""
    r = json.loads(table.read_text(encoding="utf-8"))
    nb = len(S.Z_EDGES) - 1

    def q(c, b):
        zc = (S.Z_EDGES[b] + S.Z_EDGES[b + 1]) / 2
        # 묶음(실내·O01·T)마다 97.7% 분위수의 최댓값(stage0.margin_table과 같은 규칙)
        vs = [x["q977"] for x in r if x["group"] in ("indoor", "o01", "t") and x["where"] == "out" and x["conf"] == c and abs(x["zC"] - zc) < 1e-6]
        return max(vs) if vs else np.inf

    mt = {c: np.array([q(c, b) for b in range(nb)]) for c in ("hi", "lo")}
    print("margins hi", mt["hi"].round(3), "lo", mt["lo"].round(3), file=sys.stderr)
    rows = []
    for run in runs:
        for info, p, zc, pconf, ok, diff, inband, gates in frames_of(run):
            zb = np.clip(np.digitize(zc, S.Z_EDGES) - 1, 0, nb - 1)
            hi = pconf >= 128
            m = np.where(hi, mt["hi"][zb], mt["lo"][zb])
            use = ok & ~inband
            vio = use & (diff > m)
            for kind, gid, sel, extra in gates:
                n = int(sel.sum())
                if n == 0:
                    continue
                rows.append(dict(**info, kind=kind, gate=gid, nPts=n, zMed=float(np.median(zc[sel])),
                                 nCmpHi=int((use & hi)[sel].sum()), nViolHi=int((vio & hi)[sel].sum()),
                                 nCmpLo=int((use & ~hi)[sel].sum()), nViolLo=int((vio & ~hi)[sel].sum()), **extra))
        print(f"{run.name} rows {len(rows)}", file=sys.stderr)
    out.parent.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(rows).to_csv(out, index=False)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["sigma", "viol"])
    ap.add_argument("runs", nargs="+", type=Path)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--table", type=Path)
    a = ap.parse_args()
    if a.cmd == "sigma":
        sigma(a.runs, a.out)
    else:
        viol(a.runs, a.out, a.table)


if __name__ == "__main__":
    main()
