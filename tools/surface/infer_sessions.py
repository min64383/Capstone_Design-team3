"""세션 RGB에 노면 분할 모델을 돌려 `surface/`(core SurfaceFrames가 읽는 형식)를 쓴다(M50 설계 3.3절, PC 재생 전용).

- 입력: `frames.csv`의 rgbFile(센서 방향 640×480). 세로로 돌려(시계 방향 90°) 아래 60%(지평선 아래)를 320×256으로 줄여 모델에 넣는다.
- 빈도: 노면 영상 사이 최소 200 ms(5 Hz). 과거 장만 쓴다(각 영상은 자기 장만 본다).
- 출력: 센서 방향 320×240 8비트 PNG(화소 = SurfaceClass 번호, 위 40%는 계산 안 함 255), `surface/index.csv`(frameTNs,imageTNs,width,height,file).

사용: python tools/surface/infer_sessions.py --weights ckpt.pt --arch large <세션 폴더>...
"""
import argparse
import csv
import os
import sys

import numpy as np
import torch
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from export import build  # noqa: E402

MEAN = np.array([0.485, 0.456, 0.406], np.float32)
STD = np.array([0.229, 0.224, 0.225], np.float32)
CROP_TOP = 0.4  # 세로 영상에서 버리는 위쪽 몫(설계 3.3절: 아래 60%)
IN_W, IN_H = 320, 256
OUT_W, OUT_H = 320, 240  # 센서 방향 출력(CPU 영상 640×480의 절반)
UNKNOWN = 255
MIN_GAP_NS = 200_000_000


def portrait_input(img: Image.Image) -> np.ndarray:
    """센서 방향 RGB → 세로 영상 아래 60% → 모델 입력 1×3×256×320."""
    p = img.convert("RGB").rotate(-90, expand=True)  # 시계 방향 90°: 세로로 선 영상
    w, h = p.size
    crop = p.crop((0, int(h * CROP_TOP), w, h)).resize((IN_W, IN_H), Image.BILINEAR)
    x = (np.asarray(crop, np.float32) / 255.0 - MEAN) / STD
    return x.transpose(2, 0, 1)[None]


def to_sensor(labels: np.ndarray) -> np.ndarray:
    """모델 출력 256×320(세로 영상 아래 60%) → 센서 방향 240×320, 계산 안 한 곳은 255."""
    ph, pw = OUT_W, OUT_H  # 세로 영상 절반 크기: 높이 320, 폭 240
    top = int(round(ph * CROP_TOP))
    full = np.full((ph, pw), UNKNOWN, np.uint8)
    full[top:] = np.asarray(Image.fromarray(labels.astype(np.uint8)).resize((pw, ph - top), Image.NEAREST))
    return np.asarray(Image.fromarray(full).rotate(90, expand=True))  # 반시계 90°: 센서 방향으로 되돌림


def run(session: str, net: torch.nn.Module, device: str):
    rows = [r for r in csv.DictReader(open(os.path.join(session, "frames.csv"))) if r.get("rgbFile")]
    out = os.path.join(session, "surface")
    os.makedirs(out, exist_ok=True)
    index = ["frameTNs,imageTNs,width,height,file"]
    last = None
    for r in rows:
        t = int(r["tNs"])
        if last is not None and t - last < MIN_GAP_NS:
            continue
        last = t
        x = torch.from_numpy(portrait_input(Image.open(os.path.join(session, r["rgbFile"])))).to(device)
        x = torch.cat([x, torch.zeros_like(x[:, :1])], 1)
        with torch.no_grad():
            lab = net(x).argmax(1)[0].cpu().numpy()
        Image.fromarray(to_sensor(lab)).save(os.path.join(out, f"{t}.png"))
        index.append(f"{t},{t},{OUT_W},{OUT_H},surface/{t}.png")
    open(os.path.join(out, "index.csv"), "w").write("\n".join(index) + "\n")
    print(session, len(index) - 1, "images")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", required=True)
    ap.add_argument("--arch", choices=["large", "small"], default="large")
    ap.add_argument("sessions", nargs="+")
    a = ap.parse_args()
    from export import FourChannel
    net = build(a.arch)
    net.load_state_dict(torch.load(a.weights, map_location="cpu", weights_only=True))
    device = "cuda" if torch.cuda.is_available() else "cpu"
    model = FourChannel(net).eval().to(device)
    for s in a.sessions:
        run(s, model, device)


if __name__ == "__main__":
    main()
