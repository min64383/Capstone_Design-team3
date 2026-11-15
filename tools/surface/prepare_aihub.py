"""AI Hub 인도보행 영상 노면 마스크(Surface_1~5.zip)를 학습 폴더로 바꾼다(M50 설계 3.3절 표 4). zip은 풀지 않고 바로 읽는다.

- 원본: zip 안 `Surface_NNN/` 묶음마다 CVAT XML 하나(영상별 다각형, label·attribute·z_order)와 1920×1080 JPG.
- 마스크: XML 다각형을 z_order 오름차순(같으면 문서 순서)으로 칠한다. 칠하지 않은 곳은 배경 0. 클래스는 표 4로 합친다.
  배포 마스크 PNG(색 = 원래 클래스)는 쓰지 않고 `--check`에서 다각형 칠하기가 맞는지 견주는 데만 쓴다.
- 크기: 높이 320, 가로는 원본 비율 그대로(569×320). train.py가 무작위로 줄이고 320×256을 잘라 세로 영상 아래 60%의 비율(1.25)을 만든다.
- 검증 분할: 촬영 날짜(XML 이름의 SMmmdd) 단위. 같은 날 이어진 묶음의 경계 장이 학습·검증에 함께 들어가지 않게 한다.

사용: python -I tools/surface/prepare_aihub.py --zips <Surface_*.zip 폴더> --out <준비 폴더> [--workers 8]
     python -I tools/surface/prepare_aihub.py --zips <...> --check 40   (다각형 칠하기 대 배포 마스크 PNG)
"""
import argparse
import collections
import glob
import io
import os
import random
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from multiprocessing import Pool

import numpy as np
from PIL import Image, ImageDraw

OUT_H = 320
SRC_W, SRC_H = 1920, 1080
OUT_W = round(SRC_W * OUT_H / SRC_H)  # 569
VAL_EVERY = 6  # 날짜를 정렬해 6개마다 하나를 검증으로(37일 → 6일, 자체 선택)

# 표 4. (label, attribute) → SurfaceClass 번호. 없으면 배경 0.
CLASS = {
    ("braille_guide_blocks", "normal"): 1, ("braille_guide_blocks", "damaged"): 1,
    ("bike_lane", None): 2,
    ("caution_zone", "manhole"): 2, ("caution_zone", "grating"): 2, ("caution_zone", "tree_zone"): 2,
    ("alley", "normal"): 3, ("alley", "damaged"): 3,
    ("roadway", "normal"): 4,
    ("roadway", "crosswalk"): 5, ("alley", "crosswalk"): 5,
    ("caution_zone", "stairs"): 6,
    ("alley", "speed_bump"): 7,
}
# 표 4에 없는 값: 속성 없는 alley 90개·alley@blocks 1개는 골목으로(자체 선택, 2026-11-15 전수 집계). sidewalk는 속성 무관 인도.
CLASS[("alley", None)] = 3
CLASS[("alley", "blocks")] = 3
SAFE = re.compile(r"^[A-Za-z0-9_]+$")


def cls(label, attr):
    if label == "sidewalk":
        return 2
    return CLASS.get((label, attr), 0)


def folders(zip_paths):
    """(zip 경로, 묶음 이름, XML 이름, 날짜) 목록."""
    out = []
    for zp in zip_paths:
        for n in zipfile.ZipFile(zp).namelist():
            parts = n.split("/")
            if len(parts) == 2 and parts[1].endswith(".xml") and SAFE.match(parts[0]) and SAFE.match(parts[1][:-4]):
                m = re.match(r"\d+_(SM\d{4})_", parts[1])
                out.append((zp, parts[0], n, m.group(1) if m else parts[0]))
    return out


def rasterize(image_el, w, h):
    sx, sy = w / float(image_el.get("width")), h / float(image_el.get("height"))
    polys = [(int(p.get("z_order", 0)), i, p) for i, p in enumerate(image_el.iter("polygon"))]
    mask = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(mask)
    for _, _, p in sorted(polys, key=lambda t: (t[0], t[1])):
        a = p.find("attribute")
        pts = [(float(x) * sx, float(y) * sy) for x, y in (q.split(",") for q in p.get("points").split(";"))]
        if len(pts) >= 3:
            d.polygon(pts, fill=cls(p.get("label"), a.text if a is not None else None))
    return mask


def convert(job):
    zp, folder, xml_name, _ = job
    z = zipfile.ZipFile(zp)
    names = set(z.namelist())
    done = []
    for im in ET.fromstring(z.read(xml_name)).iter("image"):
        fn = im.get("name")
        if not (fn.endswith(".jpg") and SAFE.match(fn[:-4]) and f"{folder}/{fn}" in names):
            continue
        key = f"{folder}_{fn[:-4]}"
        img = Image.open(io.BytesIO(z.read(f"{folder}/{fn}")))
        img.draft("RGB", (OUT_W * 2, OUT_H * 2))  # JPEG를 DCT 단계에서 줄여 풀기(빠름)
        img.convert("RGB").resize((OUT_W, OUT_H), Image.BILINEAR).save(os.path.join(OUT, "images", key + ".jpg"), quality=92)
        rasterize(im, OUT_W, OUT_H).save(os.path.join(OUT, "masks", key + ".png"))
        done.append(key)
    return done


def init(out):
    global OUT
    OUT = out


def check(jobs, n):
    """배포 마스크 PNG의 색마다 우리 칠하기 클래스가 한 가지로 모이는지(원래 해상도). 다수 클래스 몫이 낮으면 z_order 해석이 틀린 것."""
    rnd = random.Random(0)
    agree = collections.defaultdict(collections.Counter)
    for zp, folder, xml_name, _ in rnd.sample(jobs, n):
        z = zipfile.ZipFile(zp)
        ims = list(ET.fromstring(z.read(xml_name)).iter("image"))
        im = rnd.choice(ims)
        png = f"{folder}/MASK/{im.get('name')[:-4]}.png"
        if png not in z.namelist():
            continue
        ref = np.asarray(Image.open(io.BytesIO(z.read(png))).convert("RGB"))
        ours = np.asarray(rasterize(im, ref.shape[1], ref.shape[0]))
        code = (ref[..., 0].astype(np.int64) << 16) | (ref[..., 1].astype(np.int64) << 8) | ref[..., 2]
        for c in np.unique(code):
            agree[int(c)].update(dict(zip(*np.unique(ours[code == c], return_counts=True))))
    total = sum(sum(v.values()) for v in agree.values())
    major = sum(max(v.values()) for v in agree.values())
    for c, v in sorted(agree.items(), key=lambda kv: -sum(kv[1].values())):
        s = sum(v.values())
        top = v.most_common(3)
        print(f"#{c:06x} px {s:>10}  우리 클래스(몫): " + ", ".join(f"{k}({cnt / s:.3f})" for k, cnt in top))
    print(f"색별 다수 클래스 몫(화소 가중) {major / total:.4f}")
    return major / total


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zips", required=True)
    ap.add_argument("--out")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--check", type=int, default=0)
    a = ap.parse_args()
    jobs = folders(sorted(glob.glob(os.path.join(a.zips, "Surface_*.zip"))))
    print(len(jobs), "묶음")
    if a.check:
        sys.exit(0 if check(jobs, a.check) > 0.97 else 1)
    os.makedirs(os.path.join(a.out, "images"), exist_ok=True)
    os.makedirs(os.path.join(a.out, "masks"), exist_ok=True)
    dates = sorted({j[3] for j in jobs})
    val_dates = set(dates[VAL_EVERY // 2::VAL_EVERY])
    split = {"train": [], "val": []}
    with Pool(a.workers, initializer=init, initargs=(a.out,)) as pool:
        for job, keys in zip(jobs, pool.imap(convert, jobs, chunksize=2)):
            split["val" if job[3] in val_dates else "train"] += keys
            print(job[1], len(keys), flush=True)
    for k, v in split.items():
        open(os.path.join(a.out, f"{k}.txt"), "w", encoding="utf-8").write("\n".join(sorted(v)) + "\n")
    print("검증 날짜", sorted(val_dates), {k: len(v) for k, v in split.items()})


if __name__ == "__main__":
    main()
