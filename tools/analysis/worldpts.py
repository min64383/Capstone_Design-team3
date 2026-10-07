"""프레임별 월드 점 (M12.0, IMPROVE_SPEC §11.1).

깊이 PNG(uint16 mm, 0 = 무효) + 깊이 내부 파라미터 + 그 프레임의 자세(ARCore GL 원본)로 역투영한다.
core `Projection.backprojectToWorld`·`SessionReader.toWorldFromCv`와 같은 규약: 픽셀 중심이 정수인 픽셀 (u, v)와 깊이 d로
C_cv 점 ((u − cx)·d/fx, (v − cy)·d/fy, d)를 만들고, GL 카메라(+y 위, −z 앞)로 바꿔(y·z 부호 반대) 자세로 월드에 놓는다.
지도는 쓰지 않는다(로컬 맵은 지난 칸을 지워 드리프트를 잴 수 없다).
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd
from PIL import Image

from spike_check import quat_to_rot

# 깊이 원천(설정 `depth.source`)마다 frames.csv의 파일 열
DEPTH_COLUMN = {"SMOOTHED": "depthFile", "RAW": "rawDepthFile"}


def depth_k(meta: dict, width: int, height: int) -> dict:
    """깊이 K: v1은 `meta.depth.intrinsics`(크기가 맞을 때), 아니면 텍스처 K를 크기 비율로(core `SessionReader.depthIntrinsics`)."""
    k = meta.get("depth", {}).get("intrinsics")
    if k and k["width"] == width and k["height"] == height:
        return k
    t = meta["camera"]["textureIntrinsics"]
    sx, sy = width / t["width"], height / t["height"]
    return {"fx": t["fx"] * sx, "fy": t["fy"] * sy, "cx": t["cx"] * sx, "cy": t["cy"] * sy, "width": width, "height": height}


def load_depth_m(path: Path) -> np.ndarray:
    """깊이 PNG → 미터(float32), 0 = 무효."""
    return np.asarray(Image.open(path), dtype=np.float32) / 1000.0


def to_world(depth_m: np.ndarray, k: dict, row, step: int = 1) -> np.ndarray:
    """깊이 한 장 → 유효 픽셀의 월드 점 (N, 3). [row]는 그 프레임의 frames.csv 행(tx..qw), [step]은 픽셀 간격(`depth.subsample`)."""
    d = depth_m[::step, ::step]
    v, u = np.mgrid[0:depth_m.shape[0]:step, 0:depth_m.shape[1]:step]
    ok = d > 0
    z = d[ok]
    p_gl = np.stack([(u[ok] - k["cx"]) * z / k["fx"], -(v[ok] - k["cy"]) * z / k["fy"], -z], axis=1)
    r = quat_to_rot(np.array([[row.qx, row.qy, row.qz, row.qw]]))[0]
    return (p_gl @ r.T + np.array([row.tx, row.ty, row.tz])).astype(np.float32)


def depth_rows(frames: pd.DataFrame, source: str = "SMOOTHED") -> pd.DataFrame:
    """추적 중이고 깊이 파일이 저장된 프레임 행."""
    col = DEPTH_COLUMN[source]
    return frames[(frames.tracking == "TRACKING") & frames[col].notna()]


def iter_world(session: Path, frames: pd.DataFrame, source: str = "SMOOTHED", step: int = 2):
    """(frames.csv 행, 월드 점, 깊이 영상 m)을 깊이 프레임 순서로."""
    meta = json.loads((session / "meta.json").read_text(encoding="utf-8"))
    col = DEPTH_COLUMN[source]
    k = None
    for _, row in depth_rows(frames, source).iterrows():
        d = load_depth_m(session / row[col])
        if k is None or (k["width"], k["height"]) != (d.shape[1], d.shape[0]):
            k = depth_k(meta, d.shape[1], d.shape[0])
        yield row, to_world(d, k, row, step), d
