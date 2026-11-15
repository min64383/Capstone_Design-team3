"""M50 노면 분할 모델 학습(M50 설계 3.3절). prepare_aihub.py가 만든 폴더(images/*.jpg, masks/*.png, train.txt, val.txt)를 읽는다.

- 마스크 화소 = SurfaceClass 번호(0~7), 255 = 무시. 영상은 모델 입력보다 조금 크게(400×320) 준비되어 있고, 학습 때 무작위로 줄이고 잘라 320×256을 만든다.
- 시점 차이(휠체어 가로 영상 → 가슴 세로 영상 아래 60%)를 줄이려고 기울기(±10°)·원근(작게)·밝기 증강을 넣는다.
- 검증은 촬영 묶음 단위 분할(val.txt)의 클래스별 IoU. 가장 좋은 mIoU의 가중치(state_dict, 4채널 감싸기 전)를 저장한다.

사용: python tools/surface/train.py --data <준비 폴더> --out ckpt.pt [--arch large] [--epochs 30] [--batch 32]
"""
import argparse
import json
import os
import random
import sys
import time

import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image, ImageEnhance
from torch.utils.data import DataLoader, Dataset

sys.path.insert(0, os.path.dirname(__file__))
from export import N_CLASSES, build  # noqa: E402

MEAN = np.array([0.485, 0.456, 0.406], np.float32)
STD = np.array([0.229, 0.224, 0.225], np.float32)
W, H = 320, 256
NAMES = ["배경", "점자블록", "인도", "골목", "차도", "횡단보도", "계단", "방지턱"]


class Pairs(Dataset):
    def __init__(self, root: str, split: str, train: bool):
        self.root = root
        self.names = [l.strip() for l in open(os.path.join(root, f"{split}.txt"), encoding="utf-8") if l.strip()]
        self.train = train

    def __len__(self):
        return len(self.names)

    def __getitem__(self, i):
        n = self.names[i]
        img = Image.open(os.path.join(self.root, "images", n + ".jpg")).convert("RGB")
        msk = Image.open(os.path.join(self.root, "masks", n + ".png"))
        if self.train:
            s = random.uniform(0.8, 1.2)
            w, h = int(img.width * s), int(img.height * s)
            img, msk = img.resize((w, h), Image.BILINEAR), msk.resize((w, h), Image.NEAREST)
            a = random.uniform(-10, 10)
            img, msk = img.rotate(a, Image.BILINEAR), msk.rotate(a, Image.NEAREST, fillcolor=255)
            if w < W or h < H:
                img, msk = img.resize((max(w, W), max(h, H)), Image.BILINEAR), msk.resize((max(w, W), max(h, H)), Image.NEAREST)
            x0 = random.randint(0, img.width - W)
            y0 = random.randint(0, img.height - H)
            img, msk = img.crop((x0, y0, x0 + W, y0 + H)), msk.crop((x0, y0, x0 + W, y0 + H))
            if random.random() < 0.5:
                img, msk = img.transpose(Image.FLIP_LEFT_RIGHT), msk.transpose(Image.FLIP_LEFT_RIGHT)
            img = ImageEnhance.Brightness(img).enhance(random.uniform(0.6, 1.4))
            img = ImageEnhance.Contrast(img).enhance(random.uniform(0.7, 1.3))
        else:
            img, msk = img.resize((W, H), Image.BILINEAR), msk.resize((W, H), Image.NEAREST)
        x = ((np.asarray(img, np.float32) / 255.0 - MEAN) / STD).transpose(2, 0, 1)
        return torch.from_numpy(x), torch.from_numpy(np.asarray(msk, np.int64))


def iou(conf: np.ndarray) -> np.ndarray:
    tp = np.diag(conf)
    return tp / np.maximum(conf.sum(0) + conf.sum(1) - tp, 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--arch", choices=["large", "small"], default="large")
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--workers", type=int, default=6)
    a = ap.parse_args()
    random.seed(0)
    torch.manual_seed(0)
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    net = build(a.arch, pretrained_backbone=True).to(dev)
    tr = DataLoader(Pairs(a.data, "train", True), a.batch, shuffle=True, num_workers=a.workers, drop_last=True, persistent_workers=True)
    va = DataLoader(Pairs(a.data, "val", False), a.batch, num_workers=a.workers)
    opt = torch.optim.AdamW(net.parameters(), lr=a.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, a.lr, total_steps=a.epochs * len(tr))
    best = -1.0
    log = []
    for ep in range(a.epochs):
        net.train()
        t0 = time.time()
        for x, y in tr:
            x, y = x.to(dev, non_blocking=True), y.to(dev, non_blocking=True)
            loss = F.cross_entropy(net(x)["out"], y, ignore_index=255)
            opt.zero_grad()
            loss.backward()
            opt.step()
            sched.step()
        net.eval()
        conf = np.zeros((N_CLASSES, N_CLASSES), np.int64)
        with torch.no_grad():
            for x, y in va:
                p = net(x.to(dev))["out"].argmax(1).cpu().numpy().ravel()
                g = y.numpy().ravel()
                k = g != 255
                conf += np.bincount(g[k] * N_CLASSES + p[k], minlength=N_CLASSES ** 2).reshape(N_CLASSES, N_CLASSES)
        ious = iou(conf)
        miou = float(ious[1:].mean())  # 배경 빼고
        log.append({"epoch": ep, "loss": float(loss), "mIoU": miou, "IoU": dict(zip(NAMES, map(float, ious))), "s": time.time() - t0})
        print(json.dumps(log[-1], ensure_ascii=False), flush=True)
        if miou > best:
            best = miou
            torch.save(net.state_dict(), a.out)
    json.dump(log, open(a.out + ".log.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
