"""M65: 학습한 노면 분할 모델을 앱용 TFLite로 바꾸고, PyTorch와 라벨이 같은지 확인한다(M65 설계 3.3절, 실험 E0).

변환 환경은 저장소 안 .venv-convert(git 밖, tools/surface/requirements-convert.txt). 길은 M50 시간 관문과 같다:
export.py(4채널 입력, hard-sigmoid → relu6) → ONNX → onnx2tf → float32 TFLite.

비교(E0):
- AI Hub 검증 묶음에서 무작위 N장(씨앗 0, 학습 검증과 같은 가운데 자르기): 화소 라벨 일치율, 정답 대비 클래스별 IoU의 차이
- 세션의 노면 영상 장(infer_sessions.py와 같은 앞처리): 화소 라벨 일치율
기준(설계 표 7): 일치 ≥ 99.5%, 클래스별 IoU 차이 ≤ 0.01.

사용: .venv-convert/python tools/surface/convert_tflite.py --weights data/models/surface_large.pt --out data/models/app/surface.tflite
      --data data/aihub/surface_prep --session data/sessions/20260928_085205_T01 [--n 1000]
M69 원래 크기 입력: --size 384x480 --out data/models/app/surface_480x384.tflite (같은 가중치를 세로 아래 60% 그대로 넣는 입력 크기로 내보냄.
비교는 같은 크기로: AI Hub는 높이 384로 줄여 가운데 480, 세션은 infer_sessions.native_input).
"""
import argparse
import csv
import hashlib
import os
import random
import shutil
import sys

import numpy as np
import torch
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from export import H, N_CLASSES, W, FourChannel, build  # noqa: E402
from infer_sessions import MEAN, STD, native_input, portrait_input  # noqa: E402

NAMES = ["배경", "점자블록", "인도", "골목", "차도", "횡단보도", "계단", "방지턱"]


def convert(weights: str, work: str, h: int = H, w: int = W):
    import onnx2tf
    net = build("large")
    net.load_state_dict(torch.load(weights, map_location="cpu", weights_only=True))
    model = FourChannel(net).eval()
    os.makedirs(work, exist_ok=True)
    onnx_path = os.path.join(work, "surface.onnx")
    torch.onnx.export(model, (torch.zeros(1, 4, h, w),), onnx_path, input_names=["input"], output_names=["logits"], opset_version=17, dynamo=False)
    onnx2tf.convert(input_onnx_file_path=onnx_path, output_folder_path=work, non_verbose=True)
    return os.path.join(work, "surface_float32.tflite"), model


def val_input(path: str, H: int = H, W: int = W) -> np.ndarray:
    """학습 검증과 같은 앞처리: 높이 H로 줄이고 가운데 W 자르기. 1×3×H×W."""
    img = Image.open(path).convert("RGB")
    w = round(img.width * H / img.height)
    img = img.resize((w, H), Image.BILINEAR)
    x0 = (w - W) // 2
    img = img.crop((x0, 0, x0 + W, H))
    return ((np.asarray(img, np.float32) / 255.0 - MEAN) / STD).transpose(2, 0, 1)[None]


def val_mask(path: str, H: int = H, W: int = W) -> np.ndarray:
    m = Image.open(path)
    w = round(m.width * H / m.height)
    m = m.resize((w, H), Image.NEAREST)
    x0 = (w - W) // 2
    return np.asarray(m.crop((x0, 0, x0 + W, H)), np.int64)


class Lite:
    def __init__(self, path: str, H: int = H, W: int = W):
        from ai_edge_litert.interpreter import Interpreter
        self.it = Interpreter(model_path=path)
        self.it.allocate_tensors()
        self.inp = self.it.get_input_details()[0]
        self.out = self.it.get_output_details()[0]
        self.nhwc = list(self.inp["shape"]) == [1, H, W, 4]
        print("tflite input", self.inp["shape"], "output", self.out["shape"], "NHWC" if self.nhwc else "NCHW")

    def __call__(self, x4: np.ndarray) -> np.ndarray:
        """x4: 1×4×H×W. 반환: H×W 라벨."""
        self.it.set_tensor(self.inp["index"], x4.transpose(0, 2, 3, 1).copy() if self.nhwc else x4)
        self.it.invoke()
        y = self.it.get_tensor(self.out["index"])[0]
        return y.argmax(-1) if y.shape[-1] == N_CLASSES else y.argmax(0)


def four(x3: np.ndarray) -> np.ndarray:
    return np.concatenate([x3, np.zeros_like(x3[:, :1])], 1).astype(np.float32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--data", required=True)
    ap.add_argument("--session", action="append", default=[])
    ap.add_argument("--n", type=int, default=1000)
    ap.add_argument("--size", default=f"{H}x{W}", help="입력 높이x폭(세로 영상), M69 원래 크기 384x480")
    a = ap.parse_args()
    ih, iw = (int(v) for v in a.size.split("x"))
    work = os.path.join(os.path.dirname(os.path.dirname(a.out)), "convert")  # 앱 자산 폴더(data/models/app) 밖
    tfl, model = convert(a.weights, work, ih, iw)
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    shutil.copyfile(tfl, a.out)
    print("wrote", a.out, os.path.getsize(a.out), "bytes sha256", hashlib.sha256(open(a.out, "rb").read()).hexdigest()[:16])
    lite = Lite(a.out, ih, iw)
    torch.set_num_threads(os.cpu_count() or 4)

    def pt(x4: np.ndarray) -> np.ndarray:
        with torch.no_grad():
            return model(torch.from_numpy(x4)).argmax(1)[0].numpy()

    names = [l.strip() for l in open(os.path.join(a.data, "val.txt"), encoding="utf-8") if l.strip()]
    random.Random(0).shuffle(names)
    names = names[: a.n]
    same = total = 0
    conf_pt = np.zeros((N_CLASSES, N_CLASSES), np.int64)
    conf_tl = np.zeros((N_CLASSES, N_CLASSES), np.int64)
    for i, n in enumerate(names):
        x4 = four(val_input(os.path.join(a.data, "images", n + ".jpg"), ih, iw))
        gt = val_mask(os.path.join(a.data, "masks", n + ".png"), ih, iw)
        p, t = pt(x4), lite(x4)
        same += int((p == t).sum())
        total += p.size
        ok = gt < N_CLASSES
        conf_pt += np.bincount(gt[ok] * N_CLASSES + p[ok], minlength=N_CLASSES ** 2).reshape(N_CLASSES, N_CLASSES)
        conf_tl += np.bincount(gt[ok] * N_CLASSES + t[ok], minlength=N_CLASSES ** 2).reshape(N_CLASSES, N_CLASSES)
        if (i + 1) % 200 == 0:
            print(f"val {i + 1}/{len(names)}")

    def iou(c):
        inter = np.diag(c).astype(np.float64)
        return inter / np.maximum(c.sum(0) + c.sum(1) - inter, 1)

    ip, it = iou(conf_pt), iou(conf_tl)
    lines = [f"E0 변환 확인: {os.path.basename(a.out)}", f"AI Hub 검증 {len(names)}장(씨앗 0): 화소 라벨 일치 {same / total * 100:.3f}%",
             "클래스 | PyTorch IoU | TFLite IoU | 차이"]
    lines += [f"{NAMES[k]} | {ip[k]:.4f} | {it[k]:.4f} | {it[k] - ip[k]:+.4f}" for k in range(N_CLASSES)]
    lines.append(f"mIoU(배경 뺌) | {ip[1:].mean():.4f} | {it[1:].mean():.4f} | {it[1:].mean() - ip[1:].mean():+.4f}")
    for s in a.session:
        rows = list(csv.DictReader(open(os.path.join(s, "surface", "index.csv"))))
        frames = {r["tNs"]: r["rgbFile"] for r in csv.DictReader(open(os.path.join(s, "frames.csv"))) if r.get("rgbFile")}
        same = total = 0
        for r in rows:
            img = Image.open(os.path.join(s, frames[r["frameTNs"]]))
            x4 = four(portrait_input(img) if (ih, iw) == (H, W) else native_input(img))
            p, t = pt(x4), lite(x4)
            same += int((p == t).sum())
            total += p.size
        lines.append(f"{os.path.basename(s)} 노면 영상 {len(rows)}장: 화소 라벨 일치 {same / total * 100:.3f}%")
    report = "\n".join(lines)
    print(report)
    open(os.path.join(work, "e0_compare.txt"), "w", encoding="utf-8").write(report + "\n")


if __name__ == "__main__":
    main()
