"""학습형 깊이 장(M37, 설계 3.3~3.6절): 카메라 영상 → 학습형 상대 역깊이 → 원시 깊이 점에 척도 맞춤 → 원시 깊이 격자·시각으로 돌려놓기.

    .venv-learned/Scripts/python tools/analysis/learned_depth.py <세션 폴더> ... [--variants midas_ga,midas_sml,zip_ga,smoothed_fill] [--force]
    .venv-learned/Scripts/python tools/analysis/learned_depth.py --selftest

분석 도구와 다른 가상 환경(.venv-learned, requirements-learned.txt)에서 돈다. 모델 코드·가중치는 data/models/(고정 커밋, docs/LICENSES.md).
실행 중에 원격 코드를 받지 않는다(torch.hub는 로컬 클론으로만, 가중치는 weights_only로만 연다).

장 고르기: 원시 깊이가 새로 도착한 행(추적 중, MP4 색 영상과 짝이 있는 행) 중 앞 장에서 MIN_GAP_NS 넘게 지난 행(최대 10 Hz).
같은 장을 모든 변형이 쓴다. 고르지 않은 원시 장은 재생에서 원시 그대로 들어간다.

척도 맞춤(학습형 변형): 그 장의 원시 화소(신뢰도 ≥ MIN_CONF)를 원시 시각 자세로 월드 점으로, 영상 시각(그 행) 자세로 영상에 투영해
모델 출력 d와 짝짓고 1/Z ≈ s·d + t를 최소제곱(역깊이 공간, MiDaS·VI-Depth 관행), 잔차가 TRIM_K × 1.4826 × MAD를 넘는 점을 한 번 빼고
다시 푼다. s ≤ 0이거나 점이 MIN_POINTS 미만이면 그 장은 만들지 않는다. 깊이 범위는 PRED_RANGE_M 밖을 무효로(VI-Depth run.py 기본값).
midas_sml은 VI-Depth 절차: MiDaS small 384 입력, 전역 맞춤 결과와 원시 점 SML_POINTS개(고르게 솎음)의 척도 발판 → SML 척도 지도.

돌려놓기: 영상 시각의 미터 깊이를 깊이 격자보다 WARP_UP배 촘촘한 광선으로 월드 점으로 바꿔 원시 시각의 깊이 격자에 투영(한 화소에 여럿이면
가장 가까운 값). full = 학습형만, fill = 원시 유효 화소(신뢰도 ≥ MIN_CONF)는 원시 값 그대로·나머지만 학습형.
smoothed_fill(대조): 같은 장에서 그 행까지 온 가장 최근 평활 깊이를 같은 방식으로 원시 격자에 옮겨 fill만 만든다(모델 없음).

출력 <세션>/learned_depth/<변형>/: full/·fill/<frameIndex>.png(uint16 mm, 0 = 무효, 신뢰도 거름은 이미 적용), src/<frameIndex>.png(fill의
화소 출처: 0 원시·1 채움), index.csv(frameIndex = 원시 장이 도착한 행, rawTNs, imageTNs, s, t, nPoints, nInliers), info.json.
재생: core `-PdepthFrom=<변형>/<full|fill>`(설계 3.6절).
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sys
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[2]
MODELS = REPO / "data" / "models"
OUT_DIR = "learned_depth"

MIN_CONF = 1                 # M32 규칙: 신뢰도 0만 버림(설정 depth.minConfidence와 같아야 함)
MIN_GAP_NS = 100_000_000     # 최대 10 Hz(설계 표 8: 원시 깊이 최저 빈도 10.0 Hz, GPU 점유)
TRIM_K = 3.0                 # 이상치 제거 배수(자체 선택, 표준 강건 추정)
MIN_POINTS = 150             # VI-Depth가 학습한 가장 낮은 점 밀도
SML_POINTS = 1500            # VI-Depth 가중치 중 가장 높은 밀도
PRED_RANGE_M = (0.1, 8.0)    # VI-Depth run.py min_pred·max_pred 기본값
SML_STATE = MODELS / "weights" / f"sml_midas_small_{SML_POINTS}_state.pt"
WARP_UP = 2                  # 돌려놓기 광선 밀도(깊이 격자 대비, 자체 선택: 시각 차 p50 ≤ 24 ms의 작은 움직임에서 구멍 줄이기)
VARIANTS = {
    "midas_ga": {"model": "midas", "size": 256},
    "midas_sml": {"model": "midas", "size": 384, "sml": True},
    "zip_ga": {"model": "zip", "size": 256},
    "smoothed_fill": {"model": None},
}


# ---------- 기하(core SessionReader·worldpts와 같은 규약) ----------

def quat_to_rot(q) -> np.ndarray:
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


class Pose:
    """ARCore GL 자세(+x 오른쪽, +y 위, −z 앞)의 월드 ↔ C_cv(+y 아래, +z 앞) 변환."""
    FLIP = np.array([1.0, -1.0, -1.0])

    def __init__(self, row: dict):
        self.r = quat_to_rot([float(row[k]) for k in ("qx", "qy", "qz", "qw")])
        self.t = np.array([float(row[k]) for k in ("tx", "ty", "tz")])

    def to_world(self, p_cv: np.ndarray) -> np.ndarray:
        return (p_cv * self.FLIP) @ self.r.T + self.t

    def to_cam(self, p_w: np.ndarray) -> np.ndarray:
        return ((p_w - self.t) @ self.r) * self.FLIP


def depth_k(meta: dict, width: int, height: int) -> dict:
    """깊이 K: v1은 meta.depth.intrinsics(크기가 맞을 때), v0은 텍스처 K를 크기 비율로(core SessionReader.depthIntrinsics, worldpts와 같음)."""
    k = meta.get("depth", {}).get("intrinsics")
    if k and k["width"] == width and k["height"] == height:
        return k
    t = meta["camera"]["textureIntrinsics"]
    sx, sy = width / t["width"], height / t["height"]
    return {"fx": t["fx"] * sx, "fy": t["fy"] * sy, "cx": t["cx"] * sx, "cy": t["cy"] * sy, "width": width, "height": height}


def nearest_past_pose(rows: list[dict], arrival: int, t_ns: int) -> int:
    """rows[0..arrival] 중 [t_ns]에 가장 가까운 행(core SessionReader.nearestPastPose와 같은 규칙)."""
    best, j = arrival, arrival - 1
    while j >= 0 and abs(int(rows[j]["tNs"]) - t_ns) <= abs(int(rows[best]["tNs"]) - t_ns):
        best, j = j, j - 1
    return best


def rays(k: dict, up: int = 1) -> tuple[np.ndarray, np.ndarray]:
    """깊이 격자의 화소 중심 광선 (x/z, y/z), [up]배 촘촘하게(화소 중심이 정수인 규약)."""
    u = (np.arange(k["width"] * up) + 0.5) / up - 0.5
    v = (np.arange(k["height"] * up) + 0.5) / up - 0.5
    uu, vv = np.meshgrid(u, v)
    return (uu - k["cx"]) / k["fx"], (vv - k["cy"]) / k["fy"]


def bilinear(img: np.ndarray, x: np.ndarray, y: np.ndarray) -> np.ndarray:
    """영상 [img]를 (x, y) 화소 좌표에서 쌍선형으로. 밖은 nan."""
    h, w = img.shape
    ok = (x >= 0) & (y >= 0) & (x <= w - 1) & (y <= h - 1)
    out = np.full(x.shape, np.nan, np.float64)
    xs, ys = x[ok], y[ok]
    x0, y0 = np.floor(xs).astype(int), np.floor(ys).astype(int)
    x1, y1 = np.minimum(x0 + 1, w - 1), np.minimum(y0 + 1, h - 1)
    fx, fy = xs - x0, ys - y0
    out[ok] = (img[y0, x0] * (1 - fx) * (1 - fy) + img[y0, x1] * fx * (1 - fy)
               + img[y1, x0] * (1 - fx) * fy + img[y1, x1] * fx * fy)
    return out


def splat(p_cv: np.ndarray, k: dict) -> np.ndarray:
    """C_cv 점을 깊이 격자에 투영, 화소마다 가장 가까운 깊이(m), 없으면 0."""
    z = p_cv[:, 2]
    ok = z > 1e-6
    u = np.rint(k["fx"] * p_cv[ok, 0] / z[ok] + k["cx"]).astype(int)
    v = np.rint(k["fy"] * p_cv[ok, 1] / z[ok] + k["cy"]).astype(int)
    zz = z[ok]
    inside = (u >= 0) & (v >= 0) & (u < k["width"]) & (v < k["height"])
    out = np.full(k["width"] * k["height"], np.inf)
    np.minimum.at(out, v[inside] * k["width"] + u[inside], zz[inside])
    out[np.isinf(out)] = 0.0
    return out.reshape(k["height"], k["width"])


def fit_affine(d: np.ndarray, inv: np.ndarray) -> tuple[float, float, int]:
    """1/Z ≈ s·d + t 최소제곱, 잔차가 TRIM_K × 1.4826 × MAD를 넘는 점을 한 번 빼고 다시 푼다 → (s, t, 남은 점 수)."""
    a = np.stack([d, np.ones_like(d)], axis=1)
    st = np.linalg.lstsq(a, inv, rcond=None)[0]
    r = inv - a @ st
    dev = np.abs(r - np.median(r))
    mad = 1.4826 * np.median(dev)
    keep = dev <= TRIM_K * mad if mad > 0 else np.ones_like(dev, bool)
    st = np.linalg.lstsq(a[keep], inv[keep], rcond=None)[0]
    return float(st[0]), float(st[1]), int(keep.sum())


# ---------- 모델 ----------

def _no_remote_hub():
    import torch
    orig = torch.hub.load

    def local(repo, model, *a, **kw):
        kw.pop("source", None)
        if str(repo).startswith("rwightman/gen-efficientnet-pytorch"):
            return orig(str(MODELS / "gen-efficientnet-pytorch"), model, *a, source="local", **kw)
        raise RuntimeError(f"원격 torch.hub 금지: {repo}")
    torch.hub.load = local


class Models:
    """변형에 필요한 모델만 한 번 불러 둔다. 출력은 영상 크기(480×640)의 상대 역깊이(MiDaS·ZipDepth 모두 역깊이 쪽, M37 점검)."""

    def __init__(self, names: set[str]):
        import torch
        _no_remote_hub()
        self.torch = torch
        torch.set_num_threads(4)
        self.midas = self.zip = self.sml = None
        if "midas" in names or "sml" in names:
            sys.path.insert(0, str(MODELS / "MiDaS"))
            from midas.midas_net_custom import MidasNet_small  # 경로를 주면 백본 ImageNet 가중치를 받지 않는다
            self.midas = MidasNet_small(str(MODELS / "weights" / "midas_v21_small_256.pt"), features=64, backbone="efficientnet_lite3",
                                        exportable=True, non_negative=True, blocks={"expand": True}).eval()
        if "zip" in names:
            sys.path.insert(0, str(MODELS / "ZipDepth"))
            from zipdepth.inference.predictor import DepthInference
            self.zip = DepthInference(str(MODELS / "ZipDepth" / "checkpoints" / "zipdepth_base_npu.pth"), device="cpu", input_size=256,
                                      warmup_iters=0, upsample_unfold=False)
        if "sml" in names:
            sys.path.insert(0, str(MODELS / "VI-Depth"))
            from modules.midas.midas_net_custom import MidasNet_small_videpth
            import modules.midas.transforms as vt
            self.sml = MidasNet_small_videpth(path=str(SML_STATE),  # Lightning 체크포인트에서 꺼낸 가중치(extract_sml_state.py)
                                              min_pred=PRED_RANGE_M[0], max_pred=PRED_RANGE_M[1]).eval()
            self.sml_tf = vt.get_transforms("midas_small", "void", str(SML_POINTS))["sml_model"]

    def midas_inv(self, rgb: np.ndarray, size: int) -> np.ndarray:
        import cv2
        from midas.transforms import NormalizeImage, PrepareForNet, Resize
        s = Resize(size, size, resize_target=False, keep_aspect_ratio=True, ensure_multiple_of=32, resize_method="upper_bound",
                   image_interpolation_method=cv2.INTER_CUBIC)({"image": rgb / 255.0})
        s = PrepareForNet()(NormalizeImage(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])(s))
        with self.torch.no_grad():
            y = self.midas(self.torch.from_numpy(s["image"]).unsqueeze(0))
            y = self.torch.nn.functional.interpolate(y.unsqueeze(1), size=rgb.shape[:2], mode="bicubic", align_corners=False)
        return y[0, 0].numpy().astype(np.float64)

    def zip_inv(self, rgb: np.ndarray) -> np.ndarray:
        return self.zip.infer_image(np.ascontiguousarray(rgb[:, :, ::-1])).astype(np.float64)

    def sml_inv(self, rgb: np.ndarray, ga_inv: np.ndarray, sparse_inv: np.ndarray, valid: np.ndarray) -> np.ndarray:
        """VI-Depth pipeline.run의 SML 부분: 척도 발판 → 정규화 → SML → 미터 역깊이(영상 크기)."""
        from modules.interpolator import Interpolator2D
        import modules.midas.utils as vu
        ip = Interpolator2D(pred_inv=ga_inv.astype(np.float32), sparse_depth_inv=sparse_inv.astype(np.float32), valid=valid)
        ip.generate_interpolated_scale_map(interpolate_method="linear", fill_corners=False)
        scales = vu.normalize_unit_range(ip.interpolated_scale_map.astype(np.float32))
        g = ga_inv.astype(np.float32)
        s = self.sml_tf({"image": rgb, "int_depth": g, "int_scales": scales, "int_depth_no_tf": g})
        x = self.torch.cat([s["int_depth"], s["int_scales"]], 0)
        with self.torch.no_grad():
            pred, _ = self.sml.forward(x.unsqueeze(0), s["int_depth_no_tf"].unsqueeze(0))
            pred = self.torch.nn.functional.interpolate(pred, size=rgb.shape[:2], mode="bicubic", align_corners=False)
        return pred[0, 0].numpy().astype(np.float64)


# ---------- 세션 ----------

def read_png(path: Path) -> np.ndarray:
    from PIL import Image
    return np.asarray(Image.open(path))


def write_png16(path: Path, z_m: np.ndarray) -> None:
    from PIL import Image
    mm = np.clip(np.rint(z_m * 1000.0), 0, 65535).astype(np.uint16)
    Image.fromarray(mm).save(path)


def mp4_pairs(session: Path, rows: list[dict]) -> tuple[int, dict[int, int]]:
    """MP4 영상 트랙 번호와 {행 위치: MP4 장 번호}(mp4_rgb와 같은 짝짓기, 디코딩 없이 메타데이터만)."""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import mp4_rgb
    tr = mp4_rgb.tracks((session / "arcore.mp4").read_bytes())
    vi = next(i for i, t in enumerate(tr) if t["handler"] == "vide" and (t["width"], t["height"]) == mp4_rgb.VIDEO_SIZE)
    mp4_ns = [mp4_rgb.frame_time_ns(s) for s in tr[vi + 1]["samples"]]
    return vi, {j: k for j, (k, _) in mp4_rgb.match(mp4_ns, [int(r["tNs"]) for r in rows]).items()}


def images_by_row(session: Path, vi: int, pairs: dict[int, int], want: set[int]) -> dict[int, np.ndarray]:
    """MP4 색 영상(RGB, 640×480) 중 [want] 행과 짝인 장."""
    import av
    by_sample = {k: j for j, k in pairs.items() if j in want}
    out = {}
    with av.open(str(session / "arcore.mp4")) as c:
        for k, fr in enumerate(c.decode(c.streams[vi])):
            if k in by_sample:
                out[by_sample[k]] = fr.to_ndarray(format="rgb24")
    return out


def pick_rows(rows: list[dict], has_image: set[int] | None) -> list[int]:
    """원시 깊이가 새로 도착한 추적 중 행 중에서 앞 장보다 MIN_GAP_NS 넘게 지난 행."""
    out, last_raw, last_pick = [], -1, None
    for j, r in enumerate(rows):
        if not r["rawDepthFile"] or not r["rawDepthTNs"]:
            continue
        t_raw = int(r["rawDepthTNs"])
        if t_raw <= last_raw:
            continue
        last_raw = t_raw
        if r["tracking"] != "TRACKING" or (has_image is not None and j not in has_image):
            continue
        p = nearest_past_pose(rows, j, t_raw)
        if rows[p]["tracking"] != "TRACKING":
            continue
        t = int(r["tNs"])
        if last_pick is not None and t - last_pick < MIN_GAP_NS:
            continue
        out.append(j)
        last_pick = t
    return out


def process(session: Path, variants: list[str], models: Models | None, force: bool) -> None:
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    rows = list(csv.DictReader(open(session / "frames.csv", encoding="utf-8")))
    rows.sort(key=lambda r: int(r["tNs"]))
    todo = [v for v in variants if force or not (session / OUT_DIR / v / "info.json").is_file()]
    if not todo:
        print(f"{session.name}: 이미 있음")
        return
    vi, pairs = mp4_pairs(session, rows)
    picked = pick_rows(rows, set(pairs))  # 모든 변형이 같은 장(영상 짝이 있는 행만)
    imgs = images_by_row(session, vi, pairs, set(picked)) if any(VARIANTS[v]["model"] for v in todo) else {}
    ki = meta["camera"]["imageIntrinsics"]
    for v in todo:
        out = session / OUT_DIR / v
        for sub in ("full", "fill", "src"):
            (out / sub).mkdir(parents=True, exist_ok=True)
        index, n_fail, why = [], 0, {}
        for j in picked:
            r = rows[j]
            t_raw = int(r["rawDepthTNs"])
            z_raw = read_png(session / r["rawDepthFile"]).astype(np.float64) / 1000.0
            conf = read_png(session / r["confFile"])
            kd = depth_k(meta, z_raw.shape[1], z_raw.shape[0])
            raw_ok = (z_raw > 0) & (conf >= MIN_CONF)
            pose_raw = Pose(rows[nearest_past_pose(rows, j, t_raw)])
            pose_img = Pose(r)
            xr, yr = rays(kd)
            p_raw = np.stack([xr[raw_ok] * z_raw[raw_ok], yr[raw_ok] * z_raw[raw_ok], z_raw[raw_ok]], 1)
            spec = VARIANTS[v]
            s = t = corr = float("nan")
            n_pts = n_in = 0
            if spec["model"] is None:  # 평활 채움 대조: 그 행까지 온 가장 최근 평활 깊이
                k = j
                while k >= 0 and not rows[k]["depthFile"]:
                    k -= 1
                if k < 0:
                    n_fail += 1
                    why["noSmoothed"] = why.get("noSmoothed", 0) + 1
                    continue
                z_s = read_png(session / rows[k]["depthFile"]).astype(np.float64) / 1000.0
                pose_s = Pose(rows[nearest_past_pose(rows, k, int(rows[k]["depthTNs"]))])
                ok = z_s > 0
                p = np.stack([xr[ok] * z_s[ok], yr[ok] * z_s[ok], z_s[ok]], 1)
                z_l = splat(pose_raw.to_cam(pose_s.to_world(p)), kd)
            else:
                rgb = imgs[j]
                pc = pose_img.to_cam(pose_raw.to_world(p_raw))  # 원시 점을 영상 시각 카메라로
                ui, vi_ = ki["fx"] * pc[:, 0] / pc[:, 2] + ki["cx"], ki["fy"] * pc[:, 1] / pc[:, 2] + ki["cy"]
                rel = models.midas_inv(rgb, spec["size"]) if spec["model"] == "midas" else models.zip_inv(rgb)
                d = bilinear(rel, ui, vi_)
                ok = np.isfinite(d) & (pc[:, 2] > 0)
                n_pts = int(ok.sum())
                if n_pts < MIN_POINTS:
                    n_fail += 1
                    why["fewPoints"] = why.get("fewPoints", 0) + 1
                    continue
                s, t, n_in = fit_affine(d[ok], 1.0 / pc[ok, 2])
                corr = float(np.corrcoef(d[ok], 1.0 / pc[ok, 2])[0, 1])  # 보고용(판정에 쓰지 않음)
                if s <= 0 or n_in < MIN_POINTS:
                    n_fail += 1
                    key = "scaleNotPositive" if s <= 0 else "fewInliers"
                    why[key] = why.get(key, 0) + 1
                    continue
                inv = s * rel + t
                lo, hi = 1.0 / PRED_RANGE_M[1], 1.0 / PRED_RANGE_M[0]
                if spec.get("sml"):
                    # VI-Depth: 전역 맞춤을 범위로 자르고, 원시 점 SML_POINTS개(고르게 솎음)를 영상 격자의 희소 역깊이로
                    ga = np.clip(inv, lo, hi)
                    idx = np.flatnonzero(ok)
                    idx = idx[np.linspace(0, len(idx) - 1, min(SML_POINTS, len(idx))).astype(int)]
                    sp = np.zeros(rgb.shape[:2], np.float64)
                    valid = np.zeros(rgb.shape[:2], bool)
                    uu = np.clip(np.rint(ui[idx]).astype(int), 0, rgb.shape[1] - 1)
                    vv = np.clip(np.rint(vi_[idx]).astype(int), 0, rgb.shape[0] - 1)
                    order = np.argsort(-pc[idx, 2])  # 같은 화소면 가까운 점이 남게 먼 점부터 쓴다
                    sp[vv[order], uu[order]] = 1.0 / pc[idx[order], 2]
                    valid[vv, uu] = True
                    inv = models.sml_inv(rgb, ga, sp, valid)
                # 영상 시각의 미터 깊이 → 촘촘한 광선 → 월드 → 원시 시각 격자
                xu, yu = rays(kd, WARP_UP)
                iv = bilinear(inv, ki["fx"] * xu + ki["cx"], ki["fy"] * yu + ki["cy"])
                good = np.isfinite(iv) & (iv >= lo) & (iv <= hi)
                zi = 1.0 / iv[good]
                p = np.stack([xu[good] * zi, yu[good] * zi, zi], 1)
                z_l = splat(pose_raw.to_cam(pose_img.to_world(p)), kd)
            fill = np.where(raw_ok, z_raw, z_l)
            name = f"{int(r['frameIndex']):06d}.png"
            if spec["model"] is not None:
                write_png16(out / "full" / name, z_l)
            write_png16(out / "fill" / name, fill)
            from PIL import Image
            Image.fromarray(((~raw_ok) & (z_l > 0)).astype(np.uint8)).save(out / "src" / name)
            index.append([int(r["frameIndex"]), t_raw, int(r["tNs"]), s, t, n_pts, n_in, corr])
        with open(out / "index.csv", "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["frameIndex", "rawTNs", "imageTNs", "s", "t", "nPoints", "nInliers", "corr"])
            w.writerows(index)
        info = {"variant": v, "spec": spec, "session": session.name, "picked": len(picked), "written": len(index), "failed": n_fail, "failedWhy": why,
                "constants": {"MIN_CONF": MIN_CONF, "MIN_GAP_NS": MIN_GAP_NS, "TRIM_K": TRIM_K, "MIN_POINTS": MIN_POINTS,
                              "SML_POINTS": SML_POINTS, "PRED_RANGE_M": PRED_RANGE_M, "WARP_UP": WARP_UP},
                "models": model_info(spec)}
        (out / "info.json").write_text(json.dumps(info, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"{session.name} {v}: 고른 장 {len(picked)}, 쓴 장 {len(index)}, 못 만든 장 {n_fail} {why}")


def model_info(spec: dict) -> dict:
    def sha(p: Path) -> str:
        return hashlib.sha256(p.read_bytes()).hexdigest()[:16]
    files = {"midas": MODELS / "weights" / "midas_v21_small_256.pt", "zip": MODELS / "ZipDepth" / "checkpoints" / "zipdepth_base_npu.pth"}
    out = {}
    if spec["model"]:
        out[spec["model"]] = sha(files[spec["model"]])
    if spec.get("sml"):
        out["sml"] = sha(SML_STATE)
    return out


def selftest() -> None:
    rng = np.random.default_rng(0)
    # 1) 척도 맞춤: 알고 넣은 s·t를 이상치 20%에서 1% 안으로
    d = rng.uniform(0.1, 1.0, 5000)
    inv = 2.5 * d + 0.3 + rng.normal(0, 0.005, d.size)
    bad = rng.random(d.size) < 0.2
    inv[bad] += rng.uniform(-1.5, 1.5, bad.sum())
    s, t, n = fit_affine(d, inv)
    assert abs(s - 2.5) / 2.5 < 0.01 and abs(t - 0.3) / 0.3 < 0.01, (s, t, n)
    # 2) 돌려놓기: 같은 자세면 평면 깊이가 그대로(구멍 없이)
    k = {"fx": 125.0, "fy": 125.0, "cx": 79.5, "cy": 44.5, "width": 160, "height": 90}
    row = {"qx": 0, "qy": 0, "qz": 0, "qw": 1, "tx": 0.1, "ty": 1.2, "tz": -0.3}
    pose = Pose(row)
    xu, yu = rays(k, WARP_UP)
    z = np.full(xu.shape, 2.0)
    p = np.stack([(xu * z).ravel(), (yu * z).ravel(), z.ravel()], 1)
    back = splat(pose.to_cam(pose.to_world(p)), k)
    assert np.all(np.abs(back - 2.0) < 1e-6), (back.min(), back.max())
    # 3) 자세 왕복
    q = rng.normal(size=4)
    q /= np.linalg.norm(q)
    pz = Pose(dict(zip(("qx", "qy", "qz", "qw", "tx", "ty", "tz"), [*q, 1.0, 2.0, 3.0])))
    pts = rng.normal(size=(10, 3))
    assert np.allclose(pz.to_cam(pz.to_world(pts)), pts)
    print("selftest ok")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("sessions", nargs="*", type=Path)
    ap.add_argument("--variants", default="midas_ga,midas_sml,zip_ga,smoothed_fill")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        selftest()
        return
    variants = a.variants.split(",")
    for v in variants:
        if v not in VARIANTS:
            raise SystemExit(f"모르는 변형 {v}: {list(VARIANTS)}")
    need = set()
    for v in variants:
        if VARIANTS[v]["model"]:
            need.add(VARIANTS[v]["model"])
        if VARIANTS[v].get("sml"):
            need.add("sml")
    models = Models(need) if need else None
    for s in a.sessions:
        process(s, variants, models, a.force)


if __name__ == "__main__":
    main()
