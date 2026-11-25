"""세션 RGB에 노면 분할 모델을 돌려 `surface/`(core SurfaceFrames가 읽는 형식)를 쓴다(M50 설계 3.3절, PC 재생 전용).

- 입력: `frames.csv`의 rgbFile(센서 방향 640×480). 세로로 돌려(시계 방향 90°) 아래 60%(지평선 아래)를 320×256으로 줄여 모델에 넣는다.
- 빈도: 노면 영상 사이 최소 200 ms(5 Hz). 과거 장만 쓴다(각 영상은 자기 장만 본다).
- 출력: 센서 방향 320×240 8비트 PNG(화소 = SurfaceClass 번호, 위 40%는 계산 안 함 255), `surface/index.csv`(frameTNs,imageTNs,width,height,file).

- 1920×1080 녹화(M69 `--wide`): 1/3로 줄인 640×360(초점이 앱 640×480과 같아 그 가운데 띠와 같은 화소)의 위아래에 60화소씩 빈 띠를 붙여
  640×480으로 넣고, 출력의 빈 띠 부분을 잘라 센서 방향 320×180으로 쓴다(재생은 1920×1080 내부값을 320×180으로 줄여 쓴다).

- `--native`(M69 평가): 세로 영상 아래 60%(480×384)를 줄이지 않고 그대로 넣고, 출력을 센서 방향 640×480(또는 `--wide`면 640×360)으로 쓴다.
  지금 앱은 2/3로 줄여(320×256) 넣어서 먼 가로 방향 블록 선이 1~3화소로 뭉개진다.

사용: python tools/surface/infer_sessions.py --weights ckpt.pt --arch large [--wide] [--native] <세션 폴더>...
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


def widen(img: Image.Image) -> Image.Image:
    """1920×1080 → 640×360 화소 평균 → 위아래 60화소 빈 띠를 붙인 640×480."""
    small = img.convert("RGB").resize((img.width // 3, img.height // 3), Image.BOX)
    pad = Image.new("RGB", (small.width, small.width * 3 // 4))
    pad.paste(small, (0, (pad.height - small.height) // 2))
    return pad


def native_input(img: Image.Image) -> np.ndarray:
    """센서 방향 RGB → 세로 영상 아래 60%를 줄이지 않고 1×3×384×480."""
    p = img.convert("RGB").rotate(-90, expand=True)
    w, h = p.size
    crop = p.crop((0, int(h * CROP_TOP), w, h))
    x = (np.asarray(crop, np.float32) / 255.0 - MEAN) / STD
    return x.transpose(2, 0, 1)[None]


def native_sensor(labels: np.ndarray, sensor_w: int, sensor_h: int) -> np.ndarray:
    """줄이지 않은 출력(세로 아래 60%) → 센서 방향 sensor_h×sensor_w, 계산 안 한 곳 255."""
    ph, pw = sensor_w, sensor_h  # 세로 영상: 높이 = 센서 폭
    top = ph - labels.shape[0]
    full = np.full((ph, pw), UNKNOWN, np.uint8)
    full[top:] = labels.astype(np.uint8)
    return np.asarray(Image.fromarray(full).rotate(90, expand=True))


def run(session: str, net: torch.nn.Module, device: str, wide: bool = False, native: bool = False):
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
        img = Image.open(os.path.join(session, r["rgbFile"]))
        cut = 0
        if wide:
            assert img.width == 3 * 640 and img.height == 3 * 360, f"--wide needs 1920x1080: {img.size}"
            img = widen(img)
            cut = (OUT_H - OUT_H * 360 // 480) // 2  # 출력 240행 중 빈 띠 30행씩
        x = torch.from_numpy(native_input(img) if native else portrait_input(img)).to(device)
        x = torch.cat([x, torch.zeros_like(x[:, :1])], 1)
        with torch.no_grad():
            lab = net(x).argmax(1)[0].cpu().numpy()
        if native:
            lab = native_sensor(lab, img.width, img.height)
            cut2 = (img.height - img.height * 360 // 480) // 2 if wide else 0
            lab = lab[cut2:img.height - cut2]
        else:
            lab = to_sensor(lab)[cut:OUT_H - cut]
        Image.fromarray(lab).save(os.path.join(out, f"{t}.png"))
        index.append(f"{t},{t},{lab.shape[1]},{lab.shape[0]},surface/{t}.png")
    open(os.path.join(out, "index.csv"), "w").write("\n".join(index) + "\n")
    print(session, len(index) - 1, "images")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", required=True)
    ap.add_argument("--arch", choices=["large", "small"], default="large")
    ap.add_argument("--wide", action="store_true", help="1920x1080 녹화: 1/3 + 빈 띠(M69)")
    ap.add_argument("--native", action="store_true", help="입력을 줄이지 않음(M69 평가)")
    ap.add_argument("sessions", nargs="+")
    a = ap.parse_args()
    from export import FourChannel
    net = build(a.arch)
    net.load_state_dict(torch.load(a.weights, map_location="cpu", weights_only=True))
    device = "cuda" if torch.cuda.is_available() else "cpu"
    model = FourChannel(net).eval().to(device)
    for s in a.sessions:
        run(s, model, device, a.wide, a.native)


if __name__ == "__main__":
    main()
