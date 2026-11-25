"""M73 영상 데모: 장면 영상 + 재현 소리(WAV) → 폰용 MP4(H.264 + AAC), 확인용으로 상자·거리·말을 겹친 영상.

    python tools/demo/video_demo.py [--scene <id>] [--check]

먼저 `./gradlew :viewer:videoDemo`가 videodemo.json outDir(data/videodemo/out)에 <장면>.wav·.json을 쓴다.
원본 영상은 장면 파일 source.file에서 data/videodemo/src/<id>.mp4로 한 번 받는다(Pexels License, git 밖).
--check면 <id>_check.mp4에 손으로 적은 상자(열쇠 사이 직선), core가 낸 거리·구간, 말을 겹친다(소리 같음).
"""
from __future__ import annotations

import argparse
import bisect
import json
import urllib.request
import wave
from fractions import Fraction
from pathlib import Path

import av  # 분석 도구 의존성(docs/LICENSES.md, M19)
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
DEMO = ROOT / "tools" / "demo"
FONT = "C:/Windows/Fonts/malgunbd.ttf"


def settings() -> tuple[dict, Path]:
    s = json.loads((DEMO / "videodemo.json").read_text(encoding="utf-8"))
    out = Path(s["outDir"])
    return s["encode"], out if out.is_absolute() else ROOT / out


def source(scene: dict) -> Path:
    src = ROOT / "data" / "videodemo" / "src" / f"{scene['id']}.mp4"
    if not src.is_file():
        src.parent.mkdir(parents=True, exist_ok=True)
        req = urllib.request.Request(scene["source"]["file"], headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=60) as r:
            src.write_bytes(r.read())
    return src


def box_at(keys: list, t: float):
    """열쇠 장 사이 직선(core VideoSceneRunner.truthAt과 같은 이음). 범위 밖이면 None."""
    if not keys or t < keys[0][0] or t > keys[-1][0]:
        return None
    j = max(i for i, k in enumerate(keys) if k[0] <= t)
    a, b = keys[j], keys[min(j + 1, len(keys) - 1)]
    w = (t - a[0]) / (b[0] - a[0]) if b[0] > a[0] else 0.0
    return [a[i] + (b[i] - a[i]) * w for i in range(1, 5)], (a[5] if len(a) > 5 else "H")


def render(scene: dict, enc: dict, out_dir: Path, check: bool) -> Path:
    sid = scene["id"]
    timeline = json.loads((out_dir / f"{sid}.json").read_text(encoding="utf-8"))
    with wave.open(str(out_dir / f"{sid}.wav")) as w:
        rate = w.getframerate()
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").reshape(-1, 2)
    t0, t1 = scene["clip"]["startS"], scene["clip"]["endS"]
    dst = out_dir / (f"{sid}_check.mp4" if check else f"{sid}.mp4")
    font = ImageFont.truetype(FONT, 30)
    small = ImageFont.truetype(FONT, 24)
    truth = timeline["truth"]
    tt = [r[0] for r in truth]
    with av.open(str(source(scene))) as inp, av.open(str(dst), "w", options={"movflags": "+faststart"}) as oc:
        vin = inp.streams.video[0]
        W, H = vin.codec_context.width, vin.codec_context.height
        k = min(1.0, enc["maxLongSide"] / max(W, H))
        ow, oh = int(W * k) // 2 * 2, int(H * k) // 2 * 2
        fps = vin.average_rate
        vs = oc.add_stream("libx264", rate=fps, options={"crf": str(enc["crf"]), "preset": "medium"})
        vs.width, vs.height, vs.pix_fmt = ow, oh, "yuv420p"
        vs.time_base = 1 / Fraction(fps)
        aus = oc.add_stream("aac", rate=rate, layout="stereo")
        aus.bit_rate = enc["audioKbps"] * 1000
        n = 0
        for f in inp.decode(vin):
            t = float(f.pts * vin.time_base)
            if t < t0 - 1e-6:
                continue
            if t >= t1:
                break
            img = f.to_image().resize((ow, oh), Image.LANCZOS)
            if check:
                overlay(img, scene, t, t - t0, k, timeline, truth, tt, font, small)
            frame = av.VideoFrame.from_image(img)
            frame.pts = n
            n += 1
            for p in vs.encode(frame):
                oc.mux(p)
        for p in vs.encode():
            oc.mux(p)
        # 소리: 결과 시각 0 = 원본 startS
        step = 1024
        for i in range(0, len(pcm), step):
            chunk = pcm[i:i + step]
            af = av.AudioFrame.from_ndarray(np.ascontiguousarray(chunk.reshape(1, -1)), format="s16", layout="stereo")
            af.sample_rate = rate
            af.pts = i
            for p in aus.encode(af):
                oc.mux(p)
        for p in aus.encode():
            oc.mux(p)
    return dst


def overlay(img, scene, t_src, t, k, timeline, truth, tt, font, small):
    d = ImageDraw.Draw(img)
    for o in scene.get("objects", []):
        b = box_at(o["keys"], t_src)
        if b is None:
            continue
        (u0, v0, u1, v1), cue = b
        d.rectangle([u0 * k, v0 * k, u1 * k, v1 * k], outline=(255, 220, 0), width=3)
        i = bisect.bisect_right(tt, t) - 1
        rows = [r for r in truth[max(0, i - 3):i + 1] if r[1] == o["id"]]
        if rows:
            r = rows[-1]
            band = r[6] or "명령 없음"
            txt = f"{r[2]:.1f} m · 좌우 {r[3]:+.1f} m · {r[4] if r[4] is not None else '-'} m/s · {'통로 안' if r[5] else '통로 밖'} · {band} ({cue})"
            d.text((u0 * k, max(0, v0 * k - 34)), txt, fill=(255, 220, 0), font=small, stroke_width=2, stroke_fill=(0, 0, 0))
    said = [s for s in timeline["speech"] if s[0] <= t < s[1]]
    if said:
        d.text((20, img.height - 50), "말: " + said[-1][2], fill=(255, 255, 255), font=font, stroke_width=3, stroke_fill=(0, 0, 0))
    scr = [s for s in timeline["screens"] if s[0] <= t]
    if scr:
        s = scr[-1][1]
        label = f"화면: {s['title'] or ''} {s['label'] or ''}" + (f" 화살표 {s['az']:.0f}°" if s.get("az") is not None else "")
        d.text((20, 16), label, fill=(255, 255, 255), font=small, stroke_width=2, stroke_fill=(0, 0, 0))
    d.text((img.width - 130, 16), f"{t:5.2f} s", fill=(255, 255, 255), font=small, stroke_width=2, stroke_fill=(0, 0, 0))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--scene")
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    enc, out_dir = settings()
    for f in sorted((DEMO / "scenes").glob("*.json")):
        scene = json.loads(f.read_text(encoding="utf-8"))
        if a.scene and scene["id"] != a.scene:
            continue
        dst = render(scene, enc, out_dir, a.check)
        print(f"{scene['id']}: {dst.relative_to(ROOT)} ({dst.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
