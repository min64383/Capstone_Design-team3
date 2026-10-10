"""M20 앞면 오차 진단 (IMPROVE_SPEC v0.3.20).

    python front_diag.py causes <재생 로그 폴더> ...   → 방향 오차 6° 초과 블록의 원인 몫과 비교군(6° 이하)의 대표점 칸 상태(Markdown)
    python front_diag.py front <세션 폴더> ...          → error_decomp_frames.csv·error_decomp_frames_raw.csv로 앞면 오차의
                                                          거리·물체 높이·접근 속도별 표(Markdown)

재생 로그 폴더 이름이 세션 ID다(sweep.py 출력, data/sessions/<ID>에서 세션을 찾는다). 블록·정답 짝은 방향 지표와 같은 `metrics.block_rows`.
원인(위에서 처음 맞는 것): 추적 평활(평활 전 대표점이면 6° 이하) → 시야 밖 → 확인 못 함(가려짐·깊이 없음) → 감쇠 중(빈 공간)
→ 지금 앞면 당김(이번 장 관측, 정답 앞면보다 FRONT_M 넘게 가까움) → 지금 다른 위치 → 짝 없음(군집 기록을 못 찾음).
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

from align import align, load_config, load_frames, to_truth
from error_decomp import load_cfg
from metrics import MATCH_MARGIN_M, block_rows, load_truth

# 분석 도구 파라미터(앱·core 설정이 아님)
DIR_LIMIT_DEG = 6.0  # 방향 목표(scorecard.json)
FRONT_M = 0.05  # 대표점이 정답 앞면보다 이만큼 넘게 가까우면 "앞"(M19 설계 표 3)
SIDE_M = 0.03  # 대표점이 정답 상자 좌우 밖으로 이만큼 넘으면 "옆"(M19 설계 표 3)
DIST_BINS = [0.0, 1.0, 1.5, 2.5, 99.0]  # 비교군과 같은 거리끼리 보는 구간(m)
FRONT_BIN_M = 0.5  # 앞면 표의 거리 구간
SPEED_BINS = [-9.0, 0.2, 0.5, 9.0]  # 접근 속도 구간(m/s), 0.2 아래는 서 있음
SPEED_WINDOW_S = 0.5
MIN_FRAMES = 100  # 이보다 적은 구간은 결론에 쓰지 않는다(설계 위험 표)
SESSIONS = Path(__file__).resolve().parents[2] / "data" / "sessions"
REP_COLS = {"CORRIDOR_NEAREST": "corridorNearest", "CORRIDOR_BAND": "corridorBand", "NEAREST": "nearest", "CENTROID": "centroid"}
CAUSES = ["추적 평활", "시야 밖", "확인 못 함", "감쇠 중", "지금 앞면 당김", "지금 다른 위치", "짝 없음"]
STATE_CAUSE = {"OUT_OF_VIEW": "시야 밖", "OCCLUDED": "확인 못 함", "NO_DEPTH": "확인 못 함", "FREE": "감쇠 중"}


def azimuth(px: float, pz: float, head: tuple[float, float], sgn: float) -> tuple[float, float]:
    """정답 좌표의 점 → (머리 기준 앞 거리, 방위각°, 오른쪽 +). `metrics.truth_nearest`와 같은 축."""
    lat, along = sgn * (px - head[0]), sgn * (pz - head[1])
    return along, float(np.degrees(np.arctan2(lat, along)))


def classify(state: str, rep_along: float, truth_along: float) -> str:
    """대표점 칸 상태와 앞뒤 위치 → 원인(추적 평활·짝 없음 제외)."""
    if state in STATE_CAUSE:
        return STATE_CAUSE[state]
    if state == "HIT":
        return "지금 앞면 당김" if rep_along < truth_along - FRONT_M else "지금 다른 위치"
    return "짝 없음"


def block_causes(session: Path, run_dir: Path) -> pd.DataFrame:
    """WARN·STOP 블록 중 정답과 짝지어지고 정답 최근접점이 머리 앞인 블록(방향 지표와 같은 집합)마다 원인."""
    cfg = load_config(run_dir)
    frames = load_frames(session)
    g = pd.read_csv(run_dir / "guidance.csv")
    obs = pd.read_csv(run_dir / "obstacles.csv")
    cd = pd.read_csv(run_dir / "cluster_debug.csv")
    cd = cd[~cd.filtered.astype(bool)]
    truth, estimated = load_truth(session, cfg)
    al = align(session, run_dir)
    blocks = g.drop_duplicates("tBlockNs")
    rows, _ = block_rows(session, run_dir, cfg, frames, g, obs, blocks, g.tBlockNs.min(), truth, al)
    col = REP_COLS[cfg["repPoint"]["strategy"]]
    rx, _, rz = to_truth(al, cd[col + "X"].to_numpy(), cd[col + "Y"].to_numpy(), cd[col + "Z"].to_numpy())
    cd = cd.assign(rx=rx, rz=rz)
    by_t = {t: d for t, d in cd.groupby("tCaptureNs")}
    # 칸이 마지막으로 관측된 시각의 카메라(정답 좌표 z): 그때 정답 앞면까지 거리를 잰다
    ft = frames.tNs.to_numpy()
    _, _, fz = to_truth(al, frames.tx.to_numpy(), frames.ty.to_numpy(), frames.tz.to_numpy())
    out = []
    for r in rows:
        if r["band"] not in ("WARN", "STOP") or r["match"] is None:
            continue
        v = r["tn"][r["match"]]
        if v is None or v[0] <= 0:
            continue
        err = abs(r["az"] - v[2])
        rec = {"session": run_dir.name, "estimated": estimated, "err": err, "bad": err > DIR_LIMIT_DEG, "dist": v[1],
               "cause": "짝 없음", "stateCause": "짝 없음", "state": "", "ageMs": np.nan, "rawErr": np.nan, "pairM": np.nan,
               "pos": "", "depDist": np.nan}
        d = by_t.get(r["snap"])
        if d is not None and r["rep"] is not None:
            gap = np.hypot(d.rx - r["rep"][0], d.rz - r["rep"][1])
            j = int(np.argmin(gap.to_numpy()))
            if gap.iloc[j] <= MATCH_MARGIN_M:
                c = d.iloc[j]
                state = c.repVoxelState if isinstance(c.repVoxelState, str) else ""
                rep_along, az_raw = azimuth(c.rx, c.rz, r["head"], r["sgn"])
                _, az_smooth = azimuth(r["rep"][0], r["rep"][1], r["head"], r["sgn"])
                raw_err = abs(r["az"] + (az_raw - az_smooth) - v[2])  # 같은 머리·진행 방향에서 평활 전 대표점으로 바꾼 방향 오차
                state_cause = classify(state, rep_along, v[0])
                cause = "추적 평활" if rec["bad"] and raw_err <= DIR_LIMIT_DEG else state_cause
                ob = truth[r["match"]]
                (x0, _, z0), (x1, _, z1) = ob["min"], ob["max"]
                pos = "앞" if rep_along < v[0] - FRONT_M else "옆" if not x0 - SIDE_M <= c.rx <= x1 + SIDE_M else "상자 위"
                i = min(np.searchsorted(ft, r["snap"] - c.repVoxelAgeMs * 1e6), len(ft) - 1)
                front_z = z0 if r["sgn"] > 0 else z1
                rec.update(pos=pos, depDist=float(r["sgn"] * (front_z - fz[i])))  # 칸이 마지막으로 관측될 때 카메라에서 정답 앞면까지
                rec.update(cause=cause, stateCause=state_cause, state=state, ageMs=c.repVoxelAgeMs, rawErr=raw_err, pairM=float(gap.iloc[j]))
        out.append(rec)
    return pd.DataFrame(out)


def cause_tables(df: pd.DataFrame) -> str:
    """세션별 6° 초과 블록 원인 몫과, 거리 구간별 6° 초과 vs 이하의 칸 상태 몫."""
    lines = ["### 6° 초과 블록의 원인(블록 수, 괄호는 몫 %)", "",
             "| 세션 | 블록 | " + " | ".join(CAUSES) + " | 대표점 칸 나이 p50 ms |", "|---|---|" + "---|" * (len(CAUSES) + 1)]
    bad = df[df.bad]
    for s, d in list(bad.groupby("session")) + [("전체", bad)]:
        n = len(d)
        star = " *" if s != "전체" and d.estimated.iloc[0] else ""
        cells = [f"{(d.cause == c).sum()} ({100 * (d.cause == c).mean():.0f})" for c in CAUSES]
        lines.append(f"| {s}{star} | {n} | " + " | ".join(cells) + f" | {d.ageMs.median():.0f} |")
    lines += ["", "### 거리 구간별 대표점 칸 상태(평활 규칙 없이, 몫 %): 6° 초과 / 6° 이하", "",
              "| 거리 m | 블록 초과/이하 | " + " | ".join(CAUSES[1:6]) + " |", "|---|---|" + "---|" * 5]
    for lo, hi in zip(DIST_BINS[:-1], DIST_BINS[1:]):
        d = df[(df.dist >= lo) & (df.dist < hi)]
        b, gd = d[d.bad], d[~d.bad]
        if not len(d):
            continue
        cells = [f"{100 * (b.stateCause == c).mean() if len(b) else float('nan'):.0f} / {100 * (gd.stateCause == c).mean() if len(gd) else float('nan'):.0f}"
                 for c in CAUSES[1:6]]
        lines.append(f"| {lo:.1f}–{hi:.1f} | {len(b)}/{len(gd)} | " + " | ".join(cells) + " |")
    lines += ["", "### 6° 초과 블록: 대표점 칸 위치와 그 칸이 마지막으로 관측될 때의 거리(카메라→정답 앞면)", "",
              "| 원인 | 블록 | 앞 % | 옆 % | 상자 위 % | 관측 때 거리 p50 m (p10–p90) | 칸 나이 p50 ms |", "|---|---|---|---|---|---|---|"]
    for c in CAUSES[:6]:
        d = bad[bad.cause == c]
        if len(d):
            q = d.depDist.quantile([0.1, 0.5, 0.9]).to_numpy()
            lines.append(f"| {c} | {len(d)} | " + " | ".join(f"{100 * (d.pos == p).mean():.0f}" for p in ("앞", "옆", "상자 위"))
                         + f" | {q[1]:.2f} ({q[0]:.2f}–{q[2]:.2f}) | {d.ageMs.median():.0f} |")
    lines += ["", f"짝 거리 p50 {df.pairM.median():.3f} m, p95 {df.pairM.quantile(0.95):.3f} m, 짝 없음 {(df.cause == '짝 없음').mean() * 100:.1f}%",
              "`*` = 정답 상자 좌우 위치가 추정값(방향 결론에서 따로 본다)"]
    return "\n".join(lines)


def front_frames(session: Path) -> pd.DataFrame:
    """세션 하나의 앞면 오차 프레임(평활·원시): 물체마다 거리·높이·접근 속도와 함께."""
    truth, _ = load_truth(session, load_cfg(session))
    out = []
    for source, name in (("SMOOTHED", "error_decomp_frames.csv"), ("RAW", "error_decomp_frames_raw.csv")):
        f = session / name
        if not f.is_file() or not truth:
            continue
        df = pd.read_csv(f).sort_values("tS")
        t, cz = df.tS.to_numpy(), df.camZ.to_numpy()
        h = SPEED_WINDOW_S / 2
        speed = (np.interp(t + h, t, cz) - np.interp(t - h, t, cz)) / SPEED_WINDOW_S  # 정답 +z(앞면 쪽)로 다가가는 속도
        for ob in truth:
            if ob["kind"] != "object" or ob["name"] + "_p05" not in df:
                continue
            out.append(pd.DataFrame({"session": session.name, "source": source, "object": ob["name"], "heightM": ob["max"][1] - ob["min"][1],
                                     "dist": ob["min"][2] - cz, "speed": speed, "err": df[ob["name"]], "p05": df[ob["name"] + "_p05"]}))
    return pd.concat(out) if out else pd.DataFrame()


def front_tables(df: pd.DataFrame) -> str:
    df = df.dropna(subset=["err"])
    lines = ["### 앞면 오차(m, − = 가깝게): 중앙값 / 가장 앞 점(p05) 중앙값, 괄호는 프레임 수", "",
             "| 원천 | 물체 높이 m | " + " | ".join(f"{lo:.1f}–{lo + FRONT_BIN_M:.1f} m" for lo in np.arange(0.5, 3.0, FRONT_BIN_M)) + " |",
             "|---|---|" + "---|" * 5]
    for (src, hgt), d in df.groupby(["source", "heightM"]):
        cells = []
        for lo in np.arange(0.5, 3.0, FRONT_BIN_M):
            b = d[(d.dist >= lo) & (d.dist < lo + FRONT_BIN_M)]
            mark = "" if len(b) >= MIN_FRAMES else "†"
            cells.append(f"{b.err.median():+.2f} / {b.p05.median():+.2f} ({len(b)}){mark}" if len(b) else "–")
        lines.append(f"| {src} | {hgt:.2f} | " + " | ".join(cells) + " |")
    lines += ["", "### 접근 속도별 앞면 오차(거리 1.0–2.5 m, 중앙값 / p05 중앙값, 괄호는 프레임 수)", "",
              "| 원천 | " + " | ".join(f"{lo:.1f}–{hi:.1f} m/s" for lo, hi in zip(SPEED_BINS[:-1], SPEED_BINS[1:])) + " |", "|---|---|---|---|"]
    mid = df[(df.dist >= 1.0) & (df.dist < 2.5)]
    for src, d in mid.groupby("source"):
        cells = []
        for lo, hi in zip(SPEED_BINS[:-1], SPEED_BINS[1:]):
            b = d[(d.speed >= lo) & (d.speed < hi)]
            cells.append(f"{b.err.median():+.2f} / {b.p05.median():+.2f} ({len(b)})" if len(b) else "–")
        lines.append(f"| {src} | " + " | ".join(cells) + " |")
    lines += ["", f"† = 프레임 {MIN_FRAMES}장 미만(결론에 쓰지 않음)"]
    return "\n".join(lines)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) < 3 or sys.argv[1] not in ("causes", "front"):
        sys.exit(__doc__)
    paths = [Path(a) for a in sys.argv[2:]]
    if sys.argv[1] == "causes":
        print(cause_tables(pd.concat([block_causes(SESSIONS / p.name, p) for p in paths])))
    else:
        print(front_tables(pd.concat([front_frames(p) for p in paths])))
