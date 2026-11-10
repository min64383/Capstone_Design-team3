"""M53 골든 벡터(README §4.8): 합성 의미 영상(256×144)과 세로 폰 자세에서 foot.py의 발 접점을 계산해 core 시험 입력·기대 출력으로 쓴다.
실제 녹화는 넣지 않는다. 출력: core/src/test/resources/golden/tracking/foot_contact.json, foot_contact_labels.png."""
import json, math, sys
from pathlib import Path
import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from foot import components, contact, MIN_PX, EDGE

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'core/src/test/resources/golden/tracking'
W, H = 256, 144
K = dict(fx=202.283, fy=202.998, cx=126.568, cy=68.918)  # T 텍스처 K를 256×144로 비례한 값
PERSON, VEHICLE = 10, 9
SIGMA0, SIGMA_PX, SIGMA_H = 0.064, 1.18, 0.019  # default.json의 vision.foot* 값
MAX_DIST_M = 8.0
FLOOR_Y = -1.30
CAM = np.array([0.30, 0.15, -0.20], dtype=np.float32)  # 카메라 높이 1.45 m

# 세로 폰: 영상 +u(cv +x)가 월드 아래, 광축은 월드 +z에서 5° 숙임, 약간 옆으로 돌림(10°)
pitch, yaw = math.radians(5.0), math.radians(10.0)
fwd = np.array([math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch)])
xax = np.array([math.sin(yaw) * -math.sin(pitch), -math.cos(pitch), math.cos(yaw) * -math.sin(pitch)])  # 광축에 수직인 "아래"
yax = np.cross(fwd, xax)
R = np.stack([xax, yax, fwd], axis=1).astype(np.float32)  # 월드 ← C_cv 열 = cv 축

img = np.zeros((H, W), np.uint8)
img[:, :] = 5  # 보도
img[60:72, 100:181] = PERSON       # 사람 A(약 3.8 m): 몸
img[62:65, 181:186] = PERSON       # A의 두 발(끝 두 줄 평균)
img[67:71, 181:185] = PERSON
img[20:27, 130:201] = PERSON       # 사람 B(약 3.1 m)
img[120:127, 110:141] = PERSON     # 사람 C(약 9 m) → 최대 거리 8 m 밖이라 버림
img[100:104, 90:93] = PERSON       # 작은 덩어리(12 px) → 버림
img[110:118, 200:256] = PERSON     # 땅 쪽 변에 닿음 → 버림
img[85:95, 120:171] = VEHICLE      # 차량(약 4.7 m)
img[0:5, 150:170] = PERSON         # 옆 변(v = 0)에 닿음 → 버림


def sigma(dist):
    h = CAM[1] - FLOOR_Y
    px = SIGMA_PX * (h * h + dist * dist) / (h * K['fx'])
    fl = dist / h * SIGMA_H
    return math.sqrt(SIGMA0 ** 2 + px * px + fl * fl)


expected = []
for lab in (PERSON, VEHICLE):
    for comp in components(img == lab):
        if len(comp) < MIN_PX:
            continue
        got = contact(comp, W, H, K, R, CAM, FLOOR_Y)
        if got is None or got[2] or got[1] > MAX_DIST_M:
            continue
        p, dist, _, _ = got
        expected.append(dict(label=lab, nPx=len(comp), x=float(p[0]), y=float(p[1]), z=float(p[2]), distM=dist, sigmaM=sigma(dist)))
OUT.mkdir(parents=True, exist_ok=True)
Image.fromarray(img).save(OUT / 'foot_contact_labels.png')
wfc = np.eye(4); wfc[:3, :3] = R; wfc[:3, 3] = CAM
case = dict(case='foot_contact_synthetic', source='prototypes/python/foot_contact/golden.py',
            configOverride=dict(vision=dict(footEdgePx=EDGE, footMinPx=MIN_PX, footMaxDistM=MAX_DIST_M, footSigma0M=SIGMA0, footSigmaPx=SIGMA_PX, footFloorSigmaM=SIGMA_H)),
            input=dict(labelsPng='foot_contact_labels.png', width=W, height=H, K=K, worldFromCamRowMajor=[float(v) for v in wfc.flatten()],
                       floorY=FLOOR_Y, labels=[PERSON, VEHICLE], tCaptureNs=1_000_000_000),
            expected=dict(meas=expected), tolerance={'abs': 1e-3})
(OUT / 'foot_contact.json').write_text(json.dumps(case, indent=1), encoding='utf-8')
print(json.dumps(expected, indent=1))
