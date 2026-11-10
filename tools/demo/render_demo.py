"""발표용 시연 영상: 카메라 영상 + 안내 상태 + 위에서 본 길 + 거리 그래프 + 앱과 같은 소리 → mp4 (1920×1080, 30 fps).

    python tools/demo/render_demo.py <세션 폴더 또는 ID 일부> [--out <mp4>] [--force]

먼저 PC 재생 두 번을 돌린다(결과는 data/demo/<ID>/에 두고 다시 쓰지 않는다. --force면 다시):
- `:viewer:exportSonification` — 앱과 같은 설정(기본값 + 소리 덮어쓰기)의 소리 WAV와 오디오 블록별 안내(sonification.csv)
- `:core:replay` 같은 설정 — 물체 상자(obstacles.csv)와 진행 방향·스냅샷 시각(guidance.csv)
시각 T의 화면 = 앱이 T까지 받은 카메라 장(frames.csv sysElapsedNs ≤ T) + T의 안내. 거리·방향은 core가 낸 값 그대로 그리기만 한다.
무음은 '장애물 없음'이 아니므로(MVP §2.2-5) 음원이 없을 때는 상태 이름만 쓴다.
"""
from __future__ import annotations

import argparse
import bisect
import csv
import json
import subprocess
import sys
import wave
from fractions import Fraction
from pathlib import Path

import av  # 분석 도구 의존성(docs/LICENSES.md)
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "analysis"))
import mp4_rgb  # noqa: E402  MP4 장 ↔ frames.csv 짝짓기(M19)

APP_OVERRIDE = ROOT / "app/src/main/assets/config/risk-continuous.override.json"
W, H, FPS = 1920, 1080, 30
CAM_W = 810  # 480×640 세로 카메라 영상을 높이 1080에 맞춘 폭
FONT_B, FONT_R = "C:/Windows/Fonts/malgunbd.ttf", "C:/Windows/Fonts/malgun.ttf"

BG, PANEL, TEXT, MUTED, GRID = (17, 19, 23), (29, 32, 38), (238, 240, 243), (138, 144, 154), (52, 56, 64)
BAND_C = {"STOP": (239, 68, 68), "WARN": (245, 166, 35), "SILENT": (96, 165, 250)}
BAND_KO = {"STOP": "정지", "WARN": "주의", "SILENT": "감지"}
PRIORITY = {"STOP": 0, "WARN": 1, "SILENT": 2}

_fonts: dict = {}


def font(size: int, bold: bool = True) -> ImageFont.FreeTypeFont:
    k = (size, bold)
    if k not in _fonts:
        _fonts[k] = ImageFont.truetype(FONT_B if bold else FONT_R, size)
    return _fonts[k]


def find_session(arg: str) -> Path:
    p = Path(arg)
    if p.is_dir():
        return p.resolve()
    for base in ("data/sessions", "testdata/sessions"):
        hits = sorted(d for d in (ROOT / base).glob(f"*{arg}*") if (d / "arcore.mp4").is_file())
        if len(hits) > 1:
            sys.exit(f"세션이 여럿입니다: {[h.name for h in hits]}")
        if hits:
            return hits[0]
    sys.exit(f"arcore.mp4가 있는 세션을 찾지 못했습니다: {arg}")


def gradle(*args: str) -> None:
    cmd = ["cmd", "/c", str(ROOT / "gradlew.bat")] if sys.platform == "win32" else [str(ROOT / "gradlew")]
    subprocess.run([*cmd, "-q", *args], cwd=ROOT, check=True)


def run_replays(session: Path, work: Path, force: bool) -> tuple[Path, Path]:
    export, replay = work / "export", work / "replay"
    exported = (export / "run.txt").is_file() and "status=complete" in (export / "run.txt").read_text(encoding="utf-8")
    if force or not exported:
        gradle(":viewer:exportSonification", f"-Psession={session}", f"-Pout={export}")
    if force or not (replay / "replay_done").is_file():
        gradle(":core:replay", f"-Psession={session}", f"-PoverridesFile={APP_OVERRIDE}", f"-Pout={replay}")
    return export, replay


def config(export: Path) -> dict:
    """내보낸 기본 설정에 덮어쓰기를 절 단위로 합친다(viewer와 같은 순서: 기본 → 앱 소리 → 세션 덮어쓰기)."""
    c = json.loads((export / "default-config.json").read_text(encoding="utf-8"))
    for o in (json.loads(APP_OVERRIDE.read_text(encoding="utf-8")), json.loads((export / "overrides.json").read_text(encoding="utf-8") or "{}")):
        for sec, v in o.items():
            c.setdefault(sec, {}).update(v)
    return c


def load_blocks(export: Path):
    """오디오 블록마다 (시각 s, 상태, 고른 음원 | None). 음원이 여럿이면 STOP > WARN > SILENT, 같으면 가까운 쪽."""
    blocks: dict[int, list] = {}
    with open(export / "sonification.csv", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            b = blocks.setdefault(int(r["frameStart"]), [float(r["elapsedS"]), r["state"], None])
            if r["phase"] != "COMMAND" or not r["distanceM"]:
                continue
            src = (r["band"], float(r["distanceM"]), float(r["azimuthDeg"]), int(r["obstacleId"]))
            if b[2] is None or (PRIORITY.get(src[0], 9), src[1]) < (PRIORITY.get(b[2][0], 9), b[2][1]):
                b[2] = src
    out = [blocks[k] for k in sorted(blocks)]
    return [b[0] for b in out], out


def load_replay(replay: Path):
    heading, snaps = [], []  # (tBlockNs, 값)
    with open(replay / "guidance.csv", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            t = int(r["tBlockNs"])
            if r["headingDeg"]:
                heading.append((t, float(r["headingDeg"])))
            if r["snapshotTNs"]:
                snaps.append((t, int(r["snapshotTNs"])))
    obstacles: dict[int, list[dict]] = {}
    with open(replay / "obstacles.csv", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            obstacles.setdefault(int(r["tCaptureNs"]), []).append({
                "id": int(r["id"]),
                "min": np.array([float(r["aabbMinX"]), float(r["aabbMinY"]), float(r["aabbMinZ"])]),
                "max": np.array([float(r["aabbMaxX"]), float(r["aabbMaxY"]), float(r["aabbMaxZ"])]),
                "rep": np.array([float(r[f"repCORRIDOR_BAND_{a}"]) for a in "xyz"]),
            })
    return heading, snaps, obstacles


def latest(seq: list[tuple[int, object]], t: int):
    i = bisect.bisect_right(seq, (t, float("inf"))) - 1
    return seq[i][1] if i >= 0 else None


def rot(qx, qy, qz, qw) -> np.ndarray:
    return np.array([[1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw)],
                     [2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw)],
                     [2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy)]])


def camera_frames(session: Path):
    """MP4 장을 디코딩 순서대로: (frames.csv 행, 세로로 돌린 RGB Image). 짝짓기는 mp4_rgb.extract와 같은 규칙."""
    data = (session / "arcore.mp4").read_bytes()
    tr = mp4_rgb.tracks(data)
    vi = next(i for i, t in enumerate(tr) if t["handler"] == "vide" and (t["width"], t["height"]) == mp4_rgb.VIDEO_SIZE)
    mp4_ns = [mp4_rgb.frame_time_ns(s) for s in tr[vi + 1]["samples"]]
    with open(session / "frames.csv", encoding="utf-8") as f:
        rows = sorted(csv.DictReader(f), key=lambda r: int(r["tNs"]))
    by_sample = {k: rows[j] for j, (k, _) in mp4_rgb.match(mp4_ns, [int(r["tNs"]) for r in rows]).items()}
    with av.open(str(session / "arcore.mp4")) as c:
        for k, fr in enumerate(c.decode(c.streams[vi])):
            if k in by_sample:
                yield by_sample[k], fr.to_image().rotate(-90, expand=True)


def project_box(o: dict, row: dict, K: dict) -> tuple[float, float, float, float] | None:
    """물체 상자 8꼭짓점 → 세로 카메라 화면(810×1080)의 둘레 사각형. 카메라 앞 꼭짓점이 4개 미만이면 None."""
    R = rot(*(float(row[k]) for k in ("qx", "qy", "qz", "qw")))
    t = np.array([float(row[k]) for k in ("tx", "ty", "tz")])
    s = H / K["width"]  # 640 → 1080
    pts = []
    for cx in (0, 1):
        for cy in (0, 1):
            for cz in (0, 1):
                p = R.T @ (np.array([o["max" if cx else "min"][0], o["max" if cy else "min"][1], o["max" if cz else "min"][2]]) - t)
                x, y, z = p[0], -p[1], -p[2]  # GL 카메라 → CV
                if z < 0.1:
                    continue
                u, v = K["fx"] * x / z + K["cx"], K["fy"] * y / z + K["cy"]
                pts.append(((K["height"] - 1 - v) * s, u * s))  # 시계 방향 90° 회전
    if len(pts) < 4:
        return None
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    box = (max(min(xs), 6), max(min(ys), 6), min(max(xs), CAM_W - 6), min(max(ys), H - 6))
    return box if box[2] - box[0] > 10 and box[3] - box[1] > 10 else None


def direction_ko(az: float) -> str:
    side = "오른쪽" if az > 0 else "왼쪽"
    a = abs(az)
    return "정면" if a < 10 else f"{side} 앞" if a < 60 else side if a < 120 else "뒤"


def status_text(state: str, src, ready: bool) -> tuple[str, str, tuple]:
    if state == "UNKNOWN":
        return ("준비 중" if not ready else "확인 불가"), "주변을 확인하고 있어요", MUTED
    if src is None:
        return "안내 중", "걸어갈 길을 보고 있어요", TEXT
    band, d, az, _ = src
    return BAND_KO.get(band, band), f"{d:.1f} m · {direction_ko(az)}", BAND_C.get(band, TEXT)


def render(session: Path, out: Path, force: bool, start_s: float = 0.0, end_s: float | None = None) -> None:
    work = ROOT / "data" / "demo" / session.name
    export, replay = run_replays(session, work, force)
    cfg = config(export)
    pol, cor, head_off = cfg["policy"], cfg["corridor"], cfg["head"]["offsetFromCameraM"]
    run = dict(l.split("=", 1) for l in (export / "run.txt").read_text(encoding="utf-8").splitlines() if "=" in l)
    first_ns, duration = int(run["firstBlockNs"]), float(run["durationS"])
    bt, blocks = load_blocks(export)
    heading, snaps, obstacles = load_replay(replay)
    K = json.loads((session / "meta.json").read_text(encoding="utf-8"))["camera"]["imageIntrinsics"]

    # 자를 구간(첫 오디오 블록 기준 초)과 거리 그래프용: 1/FPS마다 고른 음원 거리(없으면 None)
    k0 = int(start_s * FPS)
    k1 = min(int(duration * FPS), int(end_s * FPS) if end_s else 10**9)
    n = k1 - k0
    series = []
    for k in range(k0, k1):
        i = bisect.bisect_right(bt, k / FPS) - 1
        series.append(blocks[i][2] if i >= 0 else None)

    wav = wave.open(str(export / "audio.wav"), "rb")
    sr, ch = wav.getframerate(), wav.getnchannels()
    pcm = np.frombuffer(wav.readframes(wav.getnframes()), np.int16).reshape(-1, ch)

    out.parent.mkdir(parents=True, exist_ok=True)
    oc = av.open(str(out), "w")
    vs = oc.add_stream("libx264", rate=FPS, options={"crf": "18", "preset": "medium"})
    vs.width, vs.height, vs.pix_fmt = W, H, "yuv420p"
    as_ = oc.add_stream("aac", rate=sr, layout="stereo" if ch == 2 else "mono")

    frames = camera_frames(session)
    cur, nxt = next(frames), next(frames, None)
    ready = any(b[1] != "UNKNOWN" for b in blocks if b[0] < start_s)
    # 위에서 본 그림: 머리 = 아래 가운데, 앞 = 위
    tv_x0, tv_y0, tv_x1, tv_y1 = CAM_W + 48, 372, W - 48, 812
    ppm = (tv_y1 - tv_y0 - 30) / (pol["silentMaxM"] + 0.6)
    hx, hy = (tv_x0 + tv_x1) / 2, tv_y1 - 24
    tl_x0, tl_y0, tl_x1, tl_y1 = CAM_W + 48, 840, W - 48, 1040
    d_max = pol["silentMaxM"] + 0.5

    for k in range(k0, k1):
        t_ns = first_ns + int(k * 1e9 / FPS)
        while nxt is not None and int(nxt[0]["sysElapsedNs"]) <= t_ns:
            cur, nxt = nxt, next(frames, None)
        row, cam = cur
        i = bisect.bisect_right(bt, k / FPS) - 1
        state, src = (blocks[i][1], blocks[i][2]) if i >= 0 else ("UNKNOWN", None)
        ready = ready or state != "UNKNOWN"
        snap = obstacles.get(latest(snaps, t_ns) or -1, [])
        guided = next((o for o in snap if src and o["id"] == src[3]), None)
        color = BAND_C.get(src[0], TEXT) if src else None

        img = Image.new("RGB", (W, H), BG)
        img.paste(cam.resize((CAM_W, H), Image.LANCZOS), (0, 0))
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)

        # 카메라 위: 안내 중인 물체 상자와 거리, 경고 중이면 테두리
        if guided is not None:
            box = project_box(guided, row, K)
            if box:
                d.rounded_rectangle(box, radius=14, outline=color + (255,), width=7)
                label = f"{src[1]:.1f} m"
                tw = d.textlength(label, font=font(44))
                lx, ly = min(max(box[0], 12), CAM_W - tw - 40), max(box[1] - 70, 12)
                d.rounded_rectangle((lx, ly, lx + tw + 28, ly + 60), radius=12, fill=color + (235,))
                d.text((lx + 14, ly + 4), label, font=font(44), fill=BG)
        if src and src[0] in ("WARN", "STOP"):
            d.rectangle((0, 0, CAM_W - 1, H - 1), outline=color + (255,), width=12)

        # 오른쪽 위: 제목
        d.text((CAM_W + 48, 36), "HEARSPACE", font=font(34), fill=TEXT)
        d.text((CAM_W + 48 + d.textlength("HEARSPACE  ", font=font(34)), 40), "보행 장애물 소리 안내", font=font(30, False), fill=MUTED)
        tt = f"{(k - k0) / FPS:4.1f} s"
        d.text((W - 48 - d.textlength(tt, font=font(30, False)), 40), tt, font=font(30, False), fill=MUTED)

        # 상태 카드
        title, sub, tcol = status_text(state, src, ready)
        card = (CAM_W + 48, 104, W - 48, 336)
        if src and src[0] in ("WARN", "STOP"):
            d.rounded_rectangle(card, radius=24, fill=color + (255,))
            tcol, scol = BG, BG
        else:
            d.rounded_rectangle(card, radius=24, fill=PANEL + (255,))
            scol = MUTED
        d.text((card[0] + 44, card[1] + 22), title, font=font(118), fill=tcol)
        d.text((card[0] + 48, card[1] + 168), sub, font=font(42, False), fill=scol)

        # 위에서 본 길
        d.rounded_rectangle((tv_x0, tv_y0, tv_x1, tv_y1), radius=24, fill=PANEL + (255,))
        d.text((tv_x0 + 28, tv_y0 + 18), "위에서 본 모습", font=font(28), fill=MUTED)

        def sp(lat: float, along: float) -> tuple[float, float]:
            return hx + lat * ppm, hy - along * ppm

        half = cor["widthM"] / 2
        d.rectangle((*sp(-half, cor["lengthM"]), *sp(half, 0)), fill=(70, 110, 180, 60), outline=(90, 130, 200, 180), width=2)
        for r_m, c, lab in ((pol["stopM"], BAND_C["STOP"], "정지"), (pol["warnMaxM"], BAND_C["WARN"], "주의")):
            d.arc((hx - r_m * ppm, hy - r_m * ppm, hx + r_m * ppm, hy + r_m * ppm), 180, 360, fill=c + (200,), width=3)
            d.text((hx + r_m * ppm * 0.71 + 8, hy - r_m * ppm * 0.71 - 18), f"{lab} {r_m:.1f} m", font=font(24, False), fill=c + (255,))
        cam_p = np.array([float(row[a]) for a in ("tx", "ty", "tz")])
        hd = latest(heading, t_ns)
        if hd is not None:
            th = np.radians(hd)
            f, r = np.array([np.sin(th), 0, -np.cos(th)]), np.array([np.cos(th), 0, np.sin(th)])
            head = cam_p + r * head_off[0] + f * head_off[2]
            for o in snap:
                corners = [np.array([x, 0, z]) for x, z in ((o["min"][0], o["min"][2]), (o["max"][0], o["min"][2]), (o["max"][0], o["max"][2]), (o["min"][0], o["max"][2]))]
                poly = [sp(float((p - head) @ r), float((p - head) @ f)) for p in corners]
                if o is guided:
                    d.polygon(poly, fill=color + (150,), outline=color + (255,), width=3)
                    rp = o["rep"] - head
                    x, y = sp(float(rp @ r), float(rp @ f))
                    d.ellipse((x - 9, y - 9, x + 9, y + 9), fill=TEXT + (255,))
                else:
                    d.polygon(poly, fill=MUTED + (60,), outline=MUTED + (200,), width=2)
        d.ellipse((hx - 16, hy - 16, hx + 16, hy + 16), fill=TEXT + (255,))
        d.polygon([(hx, hy - 34), (hx - 11, hy - 14), (hx + 11, hy - 14)], fill=TEXT + (255,))

        # 거리 그래프
        d.rounded_rectangle((tl_x0, tl_y0, tl_x1, tl_y1), radius=24, fill=PANEL + (255,))
        gx0, gx1, gy0, gy1 = tl_x0 + 110, tl_x1 - 28, tl_y0 + 62, tl_y1 - 22

        def gy(dist: float) -> float:
            return gy1 - min(dist, d_max) / d_max * (gy1 - gy0)

        d.rectangle((gx0, gy(pol["stopM"]), gx1, gy1), fill=BAND_C["STOP"] + (40,))
        d.rectangle((gx0, gy(pol["warnMaxM"]), gx1, gy(pol["stopM"])), fill=BAND_C["WARN"] + (30,))
        d.text((tl_x0 + 28, tl_y0 + 18), "물체까지 거리", font=font(28), fill=MUTED)
        for v in (pol["stopM"], pol["warnMaxM"]):
            lab = f"{v:g} m"
            d.text((gx0 - 14 - d.textlength(lab, font=font(22, False)), gy(v) - 14), lab, font=font(22, False), fill=MUTED)
        prev = None
        for j, s in enumerate(series):
            x = gx0 + j / max(n - 1, 1) * (gx1 - gx0)
            p = (x, gy(s[1])) if s else None
            if p and prev:
                d.line((prev, p), fill=(BAND_C.get(s[0], TEXT) + (255,)) if j <= k - k0 else (GRID + (255,)), width=5 if j <= k - k0 else 3)
            prev = p
        px = gx0 + (k - k0) / max(n - 1, 1) * (gx1 - gx0)
        d.line((px, gy0, px, gy1), fill=TEXT + (220,), width=3)

        img = Image.alpha_composite(img.convert("RGBA"), layer).convert("RGB")
        for p in vs.encode(av.VideoFrame.from_image(img)):
            oc.mux(p)
        if k % (FPS * 5) == 0:
            print(f"  {k / FPS:5.1f} / {k1 / FPS:.1f} s", flush=True)
    for p in vs.encode():
        oc.mux(p)

    pcm = pcm[int(k0 / FPS * sr):int(k1 / FPS * sr)]
    step = 1024
    for i0 in range(0, len(pcm), step):
        chunk = np.ascontiguousarray(pcm[i0:i0 + step])
        af = av.AudioFrame.from_ndarray(chunk.reshape(1, -1), format="s16", layout="stereo" if ch == 2 else "mono")
        af.sample_rate, af.pts, af.time_base = sr, i0, Fraction(1, sr)
        for p in as_.encode(af):
            oc.mux(p)
    for p in as_.encode():
        oc.mux(p)
    oc.close()
    print(f"영상: {out}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("session")
    ap.add_argument("--out")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--start", type=float, default=0.0, help="자를 시작(첫 오디오 블록 기준 초)")
    ap.add_argument("--end", type=float, help="자를 끝(초)")
    a = ap.parse_args()
    s = find_session(a.session)
    tag = f"_{a.start:g}-{a.end:g}s" if a.start or a.end else ""
    render(s, Path(a.out) if a.out else ROOT / "data" / "demo" / f"{s.name}{tag}.mp4", a.force, a.start, a.end)


if __name__ == "__main__":
    main()
