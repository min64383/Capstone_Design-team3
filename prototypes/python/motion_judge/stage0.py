"""M56 0단계 측정(설계 표 5). 재생 없이 기존 B+W5 재생 출력, 원시 깊이, 라벨 영상, 정답으로 잰다.

    python stage0.py sigma <재생 폴더>... --out <json>                    0-1 원시끼리 깊이 차(지연 장 수별) → σ_원시(Z)
    python stage0.py viol <재생 폴더>... --out <csv> --table <sigma.json>   0-2 창(track)별 원시 장마다 비교·위반 수

0-1 결과 정지 깊이 차는 정규보다 꼬리가 두꺼워(M31 여유 넘는 몫 1~9%), 여유는 σ 맞춤의 2√2σ 대신 정지 깊이 차의 97.7% 분위수
(정규면 같은 값, 설계의 뜻 = 정지 표면 위반 2.3%)로 둔다.

재생 폴더의 세션은 replay_info.json에서 읽는다. 정지 정답: 실내·O01 세션의 모든 장애물 점, T 세션은 움직이는 정답 1 m 밖.
창: 정지 = 그 재생의 출력 장애물 상자 ± track.matchRadiusM(가장 최근 출력 장), 사람 = 정답 위치 원기둥(반지름 radiusM + matchRadiusM).
분석 로더는 tools/analysis에서 가져온다(측정용). 판정 알고리즘은 free_space.py.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools" / "analysis"))
import event_eval as E  # noqa: E402
from align import load_config  # noqa: E402
from motion_stats import FLIP, _Labels  # noqa: E402
from spike_check import quat_to_rot  # noqa: E402
from align import load_frames  # noqa: E402
from worldpts import depth_k, load_depth_m  # noqa: E402

from free_space import RawFrame, compare  # noqa: E402

LAGS = (1, 2, 3, 4, 5, 6, 7, 8, 10, 12)  # 0-1 지연 장 수
Z_EDGES = np.arange(0.5, 5.01, 0.5)
NEAR_PERSON_M = 1.0  # T: 움직이는 정답에서 이 안의 점·창은 정지 정답에서 뺀다
PERSON_TOP_M = 2.0
MAX_RANGE_M = 5.0  # map.radiusM


def session_of(run: Path) -> Path:
    return Path(json.loads((run / "replay_info.json").read_text(encoding="utf-8"))["session"])


def group_of(run: Path) -> str:
    s = str(run).replace("\\", "/")
    return "t" if "/t/" in s else "o01" if "/o01/" in s else "indoor"


def raw_frames(session: Path, meta: dict, fr: pd.DataFrame) -> list[tuple[RawFrame, int]]:
    """원시 장(처음 전달한 행 기준)과 그 도착 시각. 자세는 도착 이전 행 중 촬영 시각에 가장 가까운 행(core nearestPastPose)."""
    ft = fr.tNs.to_numpy()
    out, last = [], -1
    for i, row in enumerate(fr.itertuples(index=False)):
        if not isinstance(row.rawDepthFile, str) or row.tracking != "TRACKING" or row.rawDepthTNs <= last:
            continue
        last = int(row.rawDepthTNs)
        j = int(np.argmin(np.abs(ft[:i + 1] - last)))
        pr = fr.iloc[j]
        rot = quat_to_rot(np.array([[pr.qx, pr.qy, pr.qz, pr.qw]]))[0] @ FLIP
        d = load_depth_m(session / row.rawDepthFile)
        conf = np.array(Image.open(session / row.confFile)) if isinstance(row.confFile, str) else np.full(d.shape, 255, np.uint8)
        k = depth_k(meta, d.shape[1], d.shape[0])
        out.append((RawFrame(last, d, conf, rot, np.array([pr.tx, pr.ty, pr.tz]), k["fx"], k["fy"], k["cx"], k["cy"]), int(row.tNs)))
    return out


def points_of(f: RawFrame, min_conf: int, with_conf: bool = False):
    """원시 장의 월드 점(신뢰도 ≥ min_conf)과 그 카메라 깊이(와 신뢰도)."""
    vv, uu = np.nonzero((f.depth_m > 0) & (f.conf >= min_conf))
    z = f.depth_m[vv, uu]
    pc = np.stack([(uu - f.cx) / f.fx * z, (vv - f.cy) / f.fy * z, z], axis=1)
    return (pc @ f.rot_w_from_c.T + f.cam_w, z) + ((f.conf[vv, uu],) if with_conf else ())


def margin_table(path: Path, v_min: float, dt_s: float, group: str = "max"):
    """0-1 결과로 Z 구간마다 (되돌아볼 장 수 L, 여유 m = 정지 깊이 차 97.7% 분위수, v_min 조건 충족 여부).
    L = L·Δ ≥ q97.7(Z, L)/v_min 인 가장 작은 장 수, 없으면 잰 가장 큰 장 수."""
    allr = json.loads(path.read_text(encoding="utf-8"))
    if group == "max":  # 묶음(실내·O01·T)마다 97.7% 분위수를 내고 그 최댓값: 모든 묶음에서 정지 위반 ≤ 2.3%(O01의 서 있는 카메라 점 수백만 개가 합친 분위수를 끌어내리지 않게)
        r = []
        for x in allr:
            if x["group"] in ("indoor", "o01", "t"):
                y = next((z for z in r if z["lag"] == x["lag"] and abs(z["zC"] - x["zC"]) < 1e-6), None)
                if y is None:
                    r.append(dict(x))
                else:
                    y["q977"] = max(y["q977"], x["q977"])
    else:
        r = [x for x in allr if x["group"] == group]
    nb = len(Z_EDGES) - 1
    lag_b, m_b, ok_b = np.zeros(nb, int), np.zeros(nb), np.zeros(nb, bool)
    for b in range(nb):
        zc = float((Z_EDGES[b] + Z_EDGES[b + 1]) / 2)
        xs = sorted((x for x in r if abs(x["zC"] - zc) < 1e-6), key=lambda x: x["lag"])
        hit = next((x for x in xs if x["lag"] * dt_s >= x["q977"] / v_min), None)
        x = hit or xs[-1]
        lag_b[b], m_b[b], ok_b[b] = x["lag"], x["q977"], hit is not None
    return lag_b, m_b, ok_b


def floor_at(sp: pd.DataFrame, t_ns: int) -> float | None:
    i = int(np.searchsorted(sp.tCaptureNs.to_numpy(), t_ns, side="right")) - 1
    return float(sp.floorY.iloc[i]) if i >= 0 and np.isfinite(sp.floorY.iloc[i]) else None


def obstacle_points(p: np.ndarray, cam: np.ndarray, floor_y: float, cfg: dict) -> np.ndarray:
    """바닥 띠 위(toleranceM + tolerancePerM·수평 거리)이고 반경 안인 점."""
    hd = np.hypot(p[:, 0] - cam[0], p[:, 2] - cam[2])
    tol = cfg["floor"]["toleranceM"] + cfg["floor"]["tolerancePerM"] * hd
    return (p[:, 1] > floor_y + tol) & (hd <= MAX_RANGE_M)


def movers(session: Path):
    ev = E.load_events(session) or {}
    out = []
    for o in ev.get("objects", []):
        if o["kind"] == "moving" and o.get("added") != "check":
            fy = float(np.median([c["floorY"] for c in E.clicks_of(o)]))
            out.append((o["id"], E.moving_track(o), float(o.get("radiusM", 0.2)), fy))
    return out


def rot_deg_s(a: RawFrame, b: RawFrame) -> float:
    r = a.rot_w_from_c.T @ b.rot_w_from_c
    ang = np.degrees(np.arccos(np.clip((np.trace(r) - 1) / 2, -1.0, 1.0)))
    dt = (b.t_capture_ns - a.t_capture_ns) / 1e9
    return float(ang / dt) if dt > 0 else np.nan


def _near_person(p_xz: np.ndarray, mv: list, t_s: float) -> np.ndarray:
    near = np.zeros(len(p_xz), dtype=bool)
    for _, tr, _, _ in mv:
        r = tr(t_s)
        if r is not None:
            near |= np.hypot(p_xz[:, 0] - r[0][0], p_xz[:, 1] - r[0][1]) <= NEAR_PERSON_M
    return near


def sigma(runs: list[Path], out: Path) -> None:
    """0-1: 정지 점을 L장 앞 원시 장에 투영한 깊이 차(지난 − 지금 점의 그 카메라 깊이)를 Z 구간별로. σ = 1.4826·MAD/√2,
    위쪽 분위수 97.7%(정규면 2√2σ, 위반 여유의 뜻)·99%·99.9%."""
    acc: dict = {}
    for run in runs:
        session, grp, cfg = session_of(run), group_of(run), load_config(run)
        meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
        fr = load_frames(session).reset_index(drop=True)
        sp = pd.read_csv(run / "slow_path.csv")
        frames = raw_frames(session, meta, fr)
        mv = movers(session)
        for i, (f, _) in enumerate(frames):
            fy = floor_at(sp, f.t_capture_ns)
            if fy is None:
                continue
            p, _ = points_of(f, 30)
            keep = obstacle_points(p, f.cam_w, fy, cfg)
            if mv:
                keep &= ~_near_person(p[:, [0, 2]], mv, f.t_capture_ns / 1e9)
            p = p[keep][::2]  # 절반만(표본 수 충분, 계산 줄임)
            for lag in LAGS:
                if i - lag < 0:
                    continue
                ok, diff, z = compare(p, frames[i - lag][0], 30)
                zb = np.digitize(z[ok], Z_EDGES) - 1
                for b in np.unique(zb):
                    if 0 <= b < len(Z_EDGES) - 1:
                        acc.setdefault((grp, lag, int(b)), []).append(diff[ok][zb == b].astype(np.float32))
        print(f"{run.name} {grp} raw frames {len(frames)}", file=sys.stderr)
    for (grp, lag, b) in list(acc):  # 정지 정답 전체(세 묶음 합)
        acc.setdefault(("all", lag, b), []).extend(acc[(grp, lag, b)])
    res = []
    for (grp, lag, b), parts in sorted(acc.items()):
        d = np.concatenate(parts)
        med = float(np.median(d))
        s = 1.4826 * float(np.median(np.abs(d - med))) / np.sqrt(2)
        zc = float((Z_EDGES[b] + Z_EDGES[b + 1]) / 2)
        m31 = 2 * np.sqrt(2) * (0.0057 + 0.0091 * zc * zc)
        res.append(dict(group=grp, lag=lag, zC=zc, n=int(len(d)), median=med, sigma=s,
                        fracOverM31=float((d > m31).mean()), fracUnderMinusM31=float((d < -m31).mean()),
                        q977=float(np.quantile(d, 0.977)), q99=float(np.quantile(d, 0.99)), q999=float(np.quantile(d, 0.999))))
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


def viol(runs: list[Path], out: Path, table: Path, v_min: float, dt_s: float) -> None:
    """0-2: 원시 장마다 창별 (점 수, 비교 수, 위반 수, Z 중앙값, 라벨 수 12, 회전 속도, 사람 정답 속도·거리)."""
    lag_b, m_b, ok_b = margin_table(table, v_min, dt_s)
    print("Z bins", list(zip(Z_EDGES[:-1].round(2), lag_b, m_b.round(3), ok_b)), file=sys.stderr)
    rows = []
    for run in runs:
        session, grp, cfg = session_of(run), group_of(run), load_config(run)
        margin = cfg["track"]["matchRadiusM"]
        meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
        fr = load_frames(session).reset_index(drop=True)
        sp = pd.read_csv(run / "slow_path.csv")
        ob = pd.read_csv(run / "obstacles.csv")
        ob_t = np.sort(ob.tCaptureNs.unique())
        frames = raw_frames(session, meta, fr)
        caps = np.array([f.t_capture_ns for f, _ in frames])
        mv = movers(session)
        L = _Labels(session, fr, meta) if (session / "semantic" / "index.csv").is_file() else None
        for i, (f, arrive) in enumerate(frames):
            fy = floor_at(sp, f.t_capture_ns)
            if fy is None:
                continue
            p, zc, pconf = points_of(f, 30, with_conf=True)
            keep = obstacle_points(p, f.cam_w, fy, cfg)
            p, zc, pconf = p[keep], zc[keep], pconf[keep]
            if len(p) == 0:
                continue
            # 점마다 되돌아볼 장 수 L(Z 구간, 0-1 표)과 여유 m(Z 구간): 과거 장만
            zb = np.clip(np.digitize(zc, Z_EDGES) - 1, 0, len(Z_EDGES) - 2)
            # 장 수 L은 원시 간격 Δ(중앙값 0.1 s)로 시간 L·Δ로 바꿔 그 시각 이전 가장 최근 장을 쓴다(원시가 가끔 30 Hz로 몰려 옴)
            tgt = f.t_capture_ns - (lag_b[zb] * dt_s * 1e9).astype(np.int64)
            back = np.searchsorted(caps[:i], tgt, side="right") - 1
            cmp_ok = np.zeros(len(p), dtype=bool)
            vio = np.zeros(len(p), dtype=bool)
            for j in np.unique(back):
                if j < 0:
                    continue
                sel = back == j
                ok, diff, _ = compare(p[sel], frames[j][0], 30)
                cmp_ok[sel] = ok
                vio[sel] = ok & (diff > m_b[zb[sel]])
            conf_hi = pconf >= 128
            lab = np.full(len(p), -1)
            if L is not None:
                got = L.at(arrive)
                if got is not None and (arrive - got[0]) <= cfg["vision"]["maxLabelAgeMs"] * 1e6:
                    lab = _Labels.read(got[1], p)
            rot = rot_deg_s(frames[i - 1][0], f) if i > 0 else np.nan
            t_s = f.t_capture_ns / 1e9
            gates = []
            # 사람 정답 창
            truth = []
            for oid, tr, rad, pfy in mv:
                r = tr(t_s)
                if r is None:
                    continue
                pos, vel = r
                truth.append(pos)
                sel = (np.hypot(p[:, 0] - pos[0], p[:, 2] - pos[1]) <= rad + margin) & (p[:, 1] < pfy + PERSON_TOP_M)
                hd = float(np.hypot(pos[0] - f.cam_w[0], pos[1] - f.cam_w[2]))
                dxz = np.array([pos[0] - f.cam_w[0], pos[1] - f.cam_w[2]]) / max(hd, 1e-6)
                spd = float(np.hypot(*vel)) if np.all(np.isfinite(vel)) else np.nan
                close = float(-(vel @ dxz)) if np.all(np.isfinite(vel)) else np.nan
                gates.append(("person", oid, sel, dict(speed=spd, closing=close, hd=hd)))
            # 정지 창: 그 재생의 출력 장애물(도착 시각까지의 가장 최근 출력 장)
            k = int(np.searchsorted(ob_t, arrive, side="right")) - 1
            if k >= 0:
                for o in ob[ob.tCaptureNs == ob_t[k]].itertuples(index=False):
                    lo = np.array([o.aabbMinX, o.aabbMinY, o.aabbMinZ]) - margin
                    hi = np.array([o.aabbMaxX, o.aabbMaxY, o.aabbMaxZ]) + margin
                    sel = np.all((p >= lo) & (p <= hi), axis=1)
                    cx, cz = (o.aabbMinX + o.aabbMaxX) / 2, (o.aabbMinZ + o.aabbMaxZ) / 2
                    near = any(np.hypot(cx - q[0], cz - q[1]) <= NEAR_PERSON_M for q in truth)
                    gates.append(("near_person" if near else "static", int(o.id), sel, {}))
            for kind, gid, sel, extra in gates:
                n = int(sel.sum())
                if n == 0:
                    continue
                lc = np.bincount(lab[sel][lab[sel] >= 0], minlength=12)[:12]
                rows.append(dict(group=grp, session=session.name, tRawNs=f.t_capture_ns, kind=kind, gate=gid, nPts=n,
                                 nCmp=int(cmp_ok[sel].sum()), nViol=int(vio[sel].sum()), zMed=float(np.median(zc[sel])),
                                 nCmpHi=int((cmp_ok & conf_hi)[sel].sum()), nViolHi=int((vio & conf_hi)[sel].sum()),
                                 rotDegS=rot, **{f"lab{c}": int(lc[c]) for c in range(12)}, **extra))
        print(f"{run.name} {grp} raw frames {len(frames)} rows {len(rows)}", file=sys.stderr)
    out.parent.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(rows).to_csv(out, index=False)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["sigma", "viol"])
    ap.add_argument("runs", nargs="+", type=Path)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--table", type=Path, help="0-1 결과(sigma.json)")
    ap.add_argument("--vmin", type=float, default=0.17)
    ap.add_argument("--dt", type=float, default=0.1, help="원시 장 간격(s)")
    a = ap.parse_args()
    if a.cmd == "sigma":
        sigma(a.runs, a.out)
    else:
        viol(a.runs, a.out, a.table, a.vmin, a.dt)


if __name__ == "__main__":
    main()
