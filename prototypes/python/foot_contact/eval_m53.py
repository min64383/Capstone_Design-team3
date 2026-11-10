"""M53 판정(설계 표 9 중 발 접점 몫, 재생 기록만 읽음).

    python eval_m53.py <변형 재생 폴더(in·t·o01 아래 세션들)>...

1. 접근 판정 시각: 마감은 M57 derive.deadlines와 같은 정의(그 재생의 motion_evidence.csv, 깊이 경로가 그대로라 b0와 같다). 판정 = 그 정답 사람
   0.8 m 안의 첫 움직이는 깊이 track(eval3.py와 같음)과 첫 발 접점 출력(foot.csv O 줄 output, 칼만 위치) 중 이른 것 − 첫 깊이 증거. 발 접점은
   판정(움직임)만 된 시각도 함께 적는다.
2. 정지 물체: 발 접점 track이 움직인다고 판정된 줄(foot.csv moving)의 칼만 위치가 정답 정지 물체(점·선) 0.5 m 안이면 거짓 판정.
3. 보고: 발 접점 출력 블록 수, 깊이 쪽에 넘겨 내지 않은 줄(suppressed) 수, track 수.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "prototypes/python/motion_judge"))
sys.path.insert(0, str(ROOT / "tools/analysis"))
import derive as D  # noqa: E402
import stage0 as S  # noqa: E402
import event_eval as E  # noqa: E402


def main(runs: list[Path]) -> None:
    late, false, report = [], [], []
    for run in runs:
        if not (run / "replay_info.json").is_file():
            continue
        session = S.session_of(run)
        tag = session.name[9:15]
        foot = pd.read_csv(run / "foot.csv") if (run / "foot.csv").is_file() else pd.DataFrame()
        mv = S.movers(session)
        ev = E.load_events(session) or {"objects": []}
        statics = [E.line_samples(o) for o in ev["objects"] if o["kind"] != "moving" and o.get("added") != "check" and E.clicks_of(o)]
        if not foot.empty:
            mvf = foot[foot.moving & np.isfinite(foot.posX)]
            for tid, g in mvf.groupby("id"):
                near = [float(np.min(np.hypot(pts[:, 0][:, None] - g.posX.values, pts[:, 1][:, None] - g.posZ.values))) for pts in statics if len(pts)]
                if near and min(near) <= 0.5:
                    false.append((tag, int(tid), round(min(near), 2)))
            out = foot[(foot.kind == "O") & foot.output]
            report.append((tag, len(out), int(((foot.kind == "O") & foot.suppressed).sum()), foot.id.nunique()))
        if mv and (run / "motion_evidence.csv").is_file() and (run / "motion.csv").is_file():
            mo = pd.read_csv(run / "motion.csv")
            md = mo[mo.moving].copy()
            md["px"] = md.posX.where(np.isfinite(md.posX), md.measX)
            md["pz"] = md.posZ.where(np.isfinite(md.posZ), md.measZ)
            e = D.load([run], 0.171)
            for _, d in D.deadlines(e).iterrows():
                tr = next(t for oid, t, _, _ in mv if oid == d.person)

                def first(ts, xs, zs):
                    hit = [t for t, x, z in zip(ts, xs, zs) if np.isfinite(x) and (q := tr(t)) is not None and np.hypot(x - q[0][0], z - q[0][1]) <= 0.8]
                    return min(hit) - d.t0 if hit else None
                t_depth = first(md.tCaptureNs.values / 1e9, md.px.values, md.pz.values)
                t_out = t_judge = None
                if not foot.empty:
                    o = foot[(foot.kind == "O") & foot.output]
                    j = foot[foot.moving & np.isfinite(foot.posX)]
                    t_out = first(o.tNs.values / 1e9, o.posX.values, o.posZ.values)
                    t_judge = first(j.tNs.values / 1e9, j.posX.values, j.posZ.values)
                both = [t for t in (t_depth, t_out) if t is not None]
                r2 = lambda x: None if x is None else round(x, 2)
                late.append(dict(session=tag, person=d.person, judged=r2(min(both)) if both else None, deadline=round(d.deadline - d.t0, 2),
                                 depth=r2(t_depth), footOutput=r2(t_out), footJudged=r2(t_judge)))
    print("접근 판정(첫 깊이 증거부터 s, 음수 = 깊이 전):")
    for x in late:
        ok = x["judged"] is not None and x["judged"] <= x["deadline"]
        print(f"  {x['session']} {x['person']}: 판정 {x['judged']} 마감 {x['deadline']} {'안' if ok else '넘음'} | 깊이 {x['depth']} 발 접점 출력 {x['footOutput']} 발 접점 판정 {x['footJudged']}")
    print(f"발 접점 정지 거짓 판정 {len(false)}: {false}")
    errors(runs)
    print("발 접점 (세션, 출력 블록, 깊이에 넘긴 줄, track 수):", report)


def errors(runs: list[Path]) -> None:
    """보고(설계 5장): 구현의 발 접점 측정 − 정답 앞면(클릭 − 0.15 m) 거리 차(카메라 수평 거리, 표 3과 같은 꼴), 움직이는 발 접점 track 속도 −
    클릭 정답 속도(지난 1 s 맞춤), 손넘김 거리(발 접점 출력이 마지막으로 난 블록의 정답 앞면 거리, 그 뒤 깊이 출력이 맡음)."""
    rows, vel, hand = [], [], []
    for run in runs:
        if not (run / "foot.csv").is_file():
            continue
        session = S.session_of(run)
        mv = S.movers(session)
        if not mv:
            continue
        foot = pd.read_csv(run / "foot.csv")
        fr = pd.read_csv(session / "frames.csv").drop_duplicates("tNs").sort_values("tNs")
        cx = lambda t: (np.interp(t, fr.tNs, fr.tx), np.interp(t, fr.tNs, fr.tz))
        for r in foot[foot.kind == "M"].itertuples():
            for _, tr, _, _ in mv:
                q = tr(r.tNs / 1e9)
                if q is None or np.hypot(r.measX - q[0][0], r.measZ - q[0][1]) > 0.6:
                    continue
                x, z = cx(r.tNs)
                dt = float(np.hypot(q[0][0] - x, q[0][1] - z)) - 0.15
                rows.append(dict(session=session.name[9:15], dt=dt, e=r.distM - dt))
                if r.moving and np.isfinite(q[1]).all() and np.isfinite(r.velX):
                    vel.append(float(np.hypot(r.velX - q[1][0], r.velZ - q[1][1])))
        out = foot[(foot.kind == "O") & foot.output]
        for tid, g in out.groupby("id"):
            r = g.iloc[-1]
            for _, tr, _, _ in mv:
                q = tr(r.tNs / 1e9)
                if q is not None and np.hypot(r.posX - q[0][0], r.posZ - q[0][1]) <= 0.8:
                    x, z = cx(r.tNs)
                    hand.append((session.name[9:15], int(tid), round(float(np.hypot(q[0][0] - x, q[0][1] - z)) - 0.15, 2)))
    if rows:
        d = pd.DataFrame(rows)
        d["bin"] = pd.cut(d.dt, [1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 8.0])
        print("발 접점 측정 − 정답 앞면(m), 정답 거리 구간별 중앙값 · σ · n:")
        for b, g in d.groupby("bin", observed=True):
            print(f"  {b}: {g.e.median():+.3f} · {g.e.std():.3f} · {len(g)}")
        mid = d[(d.dt > 2.5) & (d.dt <= 4.5)]
        print(f"  2.5~4.5 m: 중앙값 {mid.e.median():+.3f} σ {mid.e.std():.3f} p95|e| {mid.e.abs().quantile(.95):.3f} n {len(mid)}; 세션별 {mid.groupby('session').e.median().round(3).to_dict()}")
    if vel:
        v = np.array(vel)
        print(f"움직이는 발 접점 track 속도 오차(m/s): 중앙값 {np.median(v):.2f} p90 {np.percentile(v, 90):.2f} n {len(v)}")
    print("손넘김(마지막 발 접점 출력의 정답 앞면 거리 m):", hand)


if __name__ == "__main__":
    main([Path(a) for a in sys.argv[1:]])
