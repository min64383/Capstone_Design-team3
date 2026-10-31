"""VI-Depth SML 체크포인트(PyTorch Lightning)에서 모델 가중치만 안전하게 꺼내 일반 state_dict로 저장한다(M37).

    .venv-learned/Scripts/python tools/analysis/extract_sml_state.py <ckpt> <출력 .pt>

체크포인트에는 Lightning 콜백 객체가 피클로 들어 있어 torch.load(weights_only=True)가 거부한다. weights_only를 끄면 임의 코드가
실행될 수 있으므로 끄지 않는다. 대신 거부된 비-torch 클래스 이름마다 동작 없는 빈 클래스를 같은 경로에 꽂아 허용 목록에 넣고
weights_only=True로 연다(빈 클래스는 상태를 __dict__에 담기만 한다). 꺼낸 `state_dict`에서 "model." 접두어를 떼어
VI-Depth BaseModel.load와 같은 키로 저장한다.
"""
from __future__ import annotations

import re
import sys
import types
from pathlib import Path

import torch


def _stub(qualname: str):
    mod_name, _, cls_name = qualname.rpartition(".")
    if mod_name.startswith("torch"):
        raise RuntimeError(f"torch 클래스는 꽂지 않는다: {qualname}")
    parts = mod_name.split(".")
    for i in range(1, len(parts) + 1):
        name = ".".join(parts[:i])
        sys.modules.setdefault(name, types.ModuleType(name))
    cls = type(cls_name, (), {"__module__": mod_name, "__setstate__": lambda self, s: self.__dict__.update(s if isinstance(s, dict) else {"_state": s})})
    setattr(sys.modules[mod_name], cls_name, cls)
    return cls


def load_safely(path: Path) -> dict:
    stubs = []
    for _ in range(50):
        try:
            with torch.serialization.safe_globals(stubs):
                return torch.load(path, map_location="cpu", weights_only=True)
        except Exception as e:  # weights_only 거부 메시지에서 클래스 이름을 읽는다
            m = re.search(r"Unsupported global: GLOBAL ([\w.]+)", str(e))
            if not m:
                raise
            stubs.append(_stub(m.group(1)))
            print("빈 클래스로 대신함:", m.group(1))
    raise RuntimeError("허용 목록이 너무 길다")


def main() -> None:
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    ck = load_safely(src)
    sd = ck["state_dict"] if "state_dict" in ck else ck
    out = {k[6:]: v for k, v in sd.items() if k.startswith("model.")}
    if not out or not all(isinstance(v, torch.Tensor) for v in out.values()):
        raise SystemExit("model. 텐서가 없다")
    torch.save(out, dst)
    print(f"{len(out)}개 텐서 → {dst}")


if __name__ == "__main__":
    main()
