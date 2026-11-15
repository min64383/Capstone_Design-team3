"""M50 노면 분할 모델(LR-ASPP MobileNetV3)을 ONNX로 내보낸다. TFLite 변환은 저장소 밖 작업 폴더의 onnx2tf로 한다(docs/LICENSES.md).

입력은 1×4×256×320(N·C·H·W, 세로 영상 아래 60%를 320×256으로 줄인 RGB + 버리는 채널 하나). onnx2tf는 3채널 입력이면 표본 영상(피클 npy)을
내려받으려 해서 막으므로(M37 기록) 4채널로 받고 넷째 채널은 무게 0으로 버린다. 출력은 1×8×256×320 로짓(클래스 순서는 core SurfaceClass와 같음).

사용: python tools/surface/export.py --out model.onnx [--arch large|small] [--weights ckpt.pt]
가중치가 없으면 무작위 초기값으로 내보낸다(기기 시간 측정은 가중치와 무관).
"""
import argparse

import torch
from torchvision.models.segmentation import lraspp_mobilenet_v3_large
from torchvision.models import mobilenet_v3_small
from torchvision.models.segmentation.lraspp import LRASPP
from torchvision.models._utils import IntermediateLayerGetter

N_CLASSES = 8  # 배경·점자블록·인도·골목·차도·횡단보도·계단·방지턱 (M50 설계 표 4)
H, W = 256, 320


def build(arch: str, pretrained_backbone: bool = False) -> torch.nn.Module:
    """[pretrained_backbone]: ImageNet 사전학습 백본(학습 시작용, torchvision 가중치를 내려받음, M50 설계 승인)."""
    if arch == "large":
        wb = "IMAGENET1K_V1" if pretrained_backbone else None
        return lraspp_mobilenet_v3_large(weights=None, weights_backbone=wb, num_classes=N_CLASSES)
    # torchvision은 Small 분할 생성기를 주지 않는다. Large와 같은 방식(stride 8·16 특징)으로 묶는다.
    backbone = mobilenet_v3_small(weights="IMAGENET1K_V1" if pretrained_backbone else None, dilated=True).features
    stage = [0] + [i for i, b in enumerate(backbone) if getattr(b, "_is_cn", False)] + [len(backbone) - 1]
    low, high = stage[-4], stage[-1]
    body = IntermediateLayerGetter(backbone, return_layers={str(low): "low", str(high): "high"})
    return LRASPP(body, backbone[low].out_channels, backbone[high].out_channels, N_CLASSES)


class FourChannel(torch.nn.Module):
    """첫 합성곱에 무게 0인 넷째 입력 채널을 더한다(결과는 3채널과 같음). 입력을 자르는 연산은 onnx2tf가 축을 잘못 옮겨 쓰지 않는다."""

    def __init__(self, net: torch.nn.Module):
        super().__init__()
        conv = next(m for m in net.modules() if isinstance(m, torch.nn.Conv2d) and m.in_channels == 3)
        w = conv.weight.data
        conv.weight = torch.nn.Parameter(torch.cat([w, torch.zeros_like(w[:, :1])], dim=1))
        conv.in_channels = 4
        self.net = net

    def forward(self, x):
        return self.net(x)["out"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--arch", choices=["large", "small"], default="large")
    ap.add_argument("--weights")
    a = ap.parse_args()
    net = build(a.arch)
    if a.weights:
        net.load_state_dict(torch.load(a.weights, map_location="cpu", weights_only=True))
    model = FourChannel(net).eval()
    x = torch.zeros(1, 4, H, W)
    torch.onnx.export(model, (x,), a.out, input_names=["input"], output_names=["logits"], opset_version=17, dynamo=False)
    print("wrote", a.out)


if __name__ == "__main__":
    main()
