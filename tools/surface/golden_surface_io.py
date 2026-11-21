"""M65 SP-15 황금 값: infer_sessions.py와 같은 Pillow 단계로 합성 영상의 앞처리·뒤처리 결과를 만든다(core SurfaceIO 시험용).

작은 경우(센서 40×30 → 20×16, 라벨 → 센서 32×24)는 바이트 전부를 16진수로, 실제 크기(640×480 → 320×256, 라벨 → 320×240)는 CRC32로 출력한다.
합성 영상 식은 core 시험과 같다: v(x, y, c) = (x·37 + y·91 + ((x·y) mod 17)·11 + c·53) mod 256, 라벨 L(x, y) = (x·3 + y·5 + (x·y) mod 7) mod 8.

사용: .venv-convert/python tools/surface/golden_surface_io.py
"""
import zlib

import numpy as np
from PIL import Image

MEAN = np.array([0.485, 0.456, 0.406], np.float32)
STD = np.array([0.229, 0.224, 0.225], np.float32)
CROP_TOP = 0.4


def synth(w: int, h: int) -> Image.Image:
    y, x = np.mgrid[0:h, 0:w]
    rgb = np.stack([(x * 37 + y * 91 + ((x * y) % 17) * 11 + c * 53) % 256 for c in range(3)], -1).astype(np.uint8)
    return Image.fromarray(rgb, "RGB")


def labels(w: int, h: int) -> np.ndarray:
    y, x = np.mgrid[0:h, 0:w]
    return ((x * 3 + y * 5 + (x * y) % 7) % 8).astype(np.uint8)


def pre(img: Image.Image, in_w: int, in_h: int):
    p = img.rotate(-90, expand=True)
    w, h = p.size
    crop = p.crop((0, int(h * CROP_TOP), w, h)).resize((in_w, in_h), Image.BILINEAR)
    return np.asarray(crop, np.uint8), (np.asarray(crop, np.float32) / 255.0 - MEAN) / STD


def post(lab: np.ndarray, out_w: int, out_h: int) -> np.ndarray:
    ph, pw = out_w, out_h
    top = int(round(ph * CROP_TOP))
    full = np.full((ph, pw), 255, np.uint8)
    full[top:] = np.asarray(Image.fromarray(lab).resize((pw, ph - top), Image.NEAREST))
    return np.asarray(Image.fromarray(full).rotate(90, expand=True))


def main():
    u8, x = pre(synth(40, 30), 20, 16)
    print("SMALL_RESIZED =", u8.tobytes().hex())
    print("SMALL_FLOATS_FIRST6 =", ", ".join(f"{v:.9g}f" for v in x.reshape(-1)[:6]))
    print("SMALL_LABELS =", post(labels(20, 16), 32, 24).tobytes().hex())
    u8, x = pre(synth(640, 480), 320, 256)
    print("FULL_RESIZED_CRC32 =", hex(zlib.crc32(u8.tobytes())))
    print("FULL_LABELS_CRC32 =", hex(zlib.crc32(post(labels(320, 256), 320, 240).tobytes())))


if __name__ == "__main__":
    main()
