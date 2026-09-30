"""실행 로그 그림 (MVP_SPEC 부록 C, M8). 정답·정렬이 있으면 정답 좌표로, 없으면 월드 좌표로 그린다.

    python plots.py <세션 폴더> <실행 로그 폴더> [--at 초 ...]
      → <실행 로그>/plots/timeseries.png, latency.png, topview.png, at_<초>.png(그 시각의 RGB + 위에서 본 물체·음원)
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from matplotlib.patches import Rectangle  # noqa: E402

from align import align, load_config, load_frames, to_truth  # noqa: E402

BAND_COLOR = {"STOP": "#d62728", "WARN": "#ff9f1c", "SILENT": "#999999"}
HCLASS_COLOR = {"FLOOR": "#2ca02c", "BODY": "#ff7f0e", "HEAD": "#9467bd"}


def frame_of(session: Path, run: Path):
    """(좌표 변환 함수, 정답 목록, 축 이름). 정렬(align.json)이 있으면 정답 좌표."""
    al_f = run / "align.json"
    truth_f = session / "annotations/obstacles.json"
    if not al_f.is_file():
        try:
            align(session, run)  # 정답이 없어도 보행선 기준으로 돌려 그린다
        except ValueError as e:
            print(f"align skipped: {e}")
    if al_f.is_file():
        al = json.loads(al_f.read_text(encoding="utf-8"))
        truth = json.loads(truth_f.read_text(encoding="utf-8"))["obstacles"] if truth_f.is_file() else []
        return (lambda x, y, z: to_truth(al, x, y, z)), truth, ("x 오른쪽 (m)", "z 보행선 앞 (m)")
    return (lambda x, y, z: (np.asarray(x), np.asarray(y), -np.asarray(z))), [], ("world x (m)", "−world z (m)")


def timeseries(g: pd.DataFrame, t0: int, out: Path):
    t = (g.tBlockNs - t0) / 1e9
    fig, ax = plt.subplots(4, 1, figsize=(10, 8), sharex=True)
    ax[0].scatter(t, g.azimuthDeg, s=2, c=[BAND_COLOR.get(b, "#1f77b4") for b in g.band.fillna("")])
    ax[0].set_ylabel("방위각 (°, 오른쪽 +)")
    ax[1].scatter(t, g.distanceM, s=2, c=[BAND_COLOR.get(b, "#1f77b4") for b in g.band.fillna("")])
    ax[1].set_ylabel("거리 (m)")
    ax[2].scatter(t, g.obstacleId, s=2)
    ax[2].set_ylabel("음원 물체 id")
    states = ["UNKNOWN", "DEGRADED", "NORMAL", "PAUSED"]
    ax[3].plot(t, g.state.map({s: i for i, s in enumerate(states)}), lw=1)
    ax[3].set_yticks(range(len(states)), states)
    ax[3].set_xlabel("시간 (s)")
    fig.tight_layout()
    fig.savefig(out / "timeseries.png", dpi=110)
    plt.close(fig)


def latency(g: pd.DataFrame, frames: pd.DataFrame, out: Path):
    blocks = g.drop_duplicates("tBlockNs")
    arrival = frames.set_index("tNs").sysElapsedNs
    pipe = ((blocks.tBlockNs - blocks.poseTNs.map(arrival)) / 1e6).dropna()
    fig, ax = plt.subplots(1, 2, figsize=(10, 3.5))
    ax[0].hist(g.infoAgeMs.dropna(), bins=40)
    ax[0].set_xlabel("정보 나이 (ms)")
    ax[1].hist(pipe, bins=40)
    ax[1].set_xlabel("앱 수신 → 오디오 블록 (ms)")
    fig.tight_layout()
    fig.savefig(out / "latency.png", dpi=110)
    plt.close(fig)


def draw_top(ax, tf, truth, frames, obs, g, t_ns=None, strat="CORRIDOR_NEAREST"):
    """위에서 본 그림: 궤적, 정답 상자, 추정 물체 AABB, 음원 대표점. [t_ns]면 그 시각 스냅샷만."""
    tr = frames[frames.tracking == "TRACKING"]
    x, _, z = tf(tr.tx, tr.ty, tr.tz)
    ax.plot(x, z, "k-", lw=1, label="카메라 궤적")
    for ob in truth:
        (x0, _, z0), (x1, _, z1) = ob["min"], ob["max"]
        ax.add_patch(Rectangle((x0, z0), x1 - x0, z1 - z0, fill=False, ec="blue", lw=2, ls="--"))
        ax.text(x0, z1 + 0.05, ob["name"], color="blue", fontsize=8)
    snap = obs if t_ns is None else obs[obs.tCaptureNs == obs.tCaptureNs[obs.tCaptureNs <= t_ns].max()]
    last = snap.groupby("id").last() if t_ns is None else snap.set_index("id")
    for oid, r in last.iterrows():
        cx, _, cz = tf([r.aabbMinX, r.aabbMaxX], [0, 0], [r.aabbMinZ, r.aabbMaxZ])
        ax.add_patch(Rectangle((min(cx), min(cz)), abs(cx[1] - cx[0]), abs(cz[1] - cz[0]),
                               fill=False, ec=HCLASS_COLOR.get(r.heightClass, "k"), lw=1, alpha=0.8))
        ax.text(max(cx), max(cz), f"#{oid}", fontsize=7)
    sel = g.dropna(subset=["obstacleId"])
    if t_ns is not None:
        sel = sel[sel.tBlockNs <= t_ns].tail(1)
    idx = obs.set_index(["tCaptureNs", "id"])
    for _, r in sel.iloc[:: max(1, len(sel) // 400)].iterrows():
        try:
            o = idx.loc[(int(r.snapshotTNs), int(r.obstacleId))]
        except KeyError:
            continue
        px, _, pz = tf(o[f"rep{strat}_x"], o[f"rep{strat}_y"], o[f"rep{strat}_z"])
        ax.plot(px, pz, "o", ms=4 if t_ns is None else 10, color=BAND_COLOR.get(r.band, "#1f77b4"))
    if t_ns is not None:
        cam = frames[frames.tNs <= t_ns].iloc[-1]
        cx, _, cz = tf(cam.tx, cam.ty, cam.tz)
        ax.plot(cx, cz, "k^", ms=10)
    ax.set_aspect("equal")


def main(session: Path, run: Path, at: list[float]):
    out = run / "plots"
    out.mkdir(exist_ok=True)
    plt.rcParams["font.family"] = ["Malgun Gothic", "DejaVu Sans"]
    plt.rcParams["axes.unicode_minus"] = False
    g = pd.read_csv(run / "guidance.csv")
    obs = pd.read_csv(run / "obstacles.csv")
    frames = load_frames(session)
    strat = load_config(run)["repPoint"]["strategy"]
    t0 = int(g.tBlockNs.min())
    tf, truth, (xl, zl) = frame_of(session, run)
    timeseries(g, t0, out)
    latency(g, frames, out)
    fig, ax = plt.subplots(figsize=(6, 8))
    draw_top(ax, tf, truth, frames, obs, g, strat=strat)
    ax.set_xlabel(xl)
    ax.set_ylabel(zl)
    ax.set_title("위에서 본 궤적·정답(파랑 점선)·추정 물체·음원 대표점")
    fig.tight_layout()
    fig.savefig(out / "topview.png", dpi=110)
    plt.close(fig)
    for s in at:
        t_ns = t0 + int(s * 1e9)
        fig, ax = plt.subplots(1, 2, figsize=(11, 7))
        row = frames[(frames.sysElapsedNs <= t_ns) & frames.rgbFile.notna()].tail(1)
        if len(row) and (session / row.rgbFile.iloc[0]).is_file():
            img = plt.imread(session / row.rgbFile.iloc[0])
            ax[0].imshow(np.rot90(img, k=-1))  # 센서 방향 → 세로 화면
        ax[0].set_axis_off()
        g_at = g[g.tBlockNs <= t_ns].tail(1)
        title = "음원 없음" if g_at.obstacleId.isna().all() else \
            f"#{int(g_at.obstacleId.iloc[0])} {g_at.band.iloc[0]} {g_at.distanceM.iloc[0]:.2f} m {g_at.azimuthDeg.iloc[0]:+.0f}°"
        ax[0].set_title(f"t = {s:.1f} s · {g_at.state.iloc[0] if len(g_at) else '-'} · {title}")
        draw_top(ax[1], tf, truth, frames, obs, g, t_ns=t_ns, strat=strat)
        ax[1].set_xlabel(xl)
        ax[1].set_ylabel(zl)
        fig.tight_layout()
        fig.savefig(out / f"at_{s:.1f}.png", dpi=100)
        plt.close(fig)
    print(f"plots: {out}")


if __name__ == "__main__":
    args = sys.argv[1:]
    if len(args) < 2:
        sys.exit(__doc__)
    at = [float(a) for a in args[args.index("--at") + 1:]] if "--at" in args else []
    main(Path(args[0]), Path(args[1]), at)
