"""Export a MobileNetV3 ImageNet classifier to a CPU/XNNPACK ExecuTorch .pte.

This is the Team B "hedge": unlike the QNN SmolVLM artifacts, an XNNPACK .pte runs on the CPU
backend already bundled in `executorch-android` — no QNN libs / runner needed. It drops straight
into `ClassifierSceneDescriber` (expects [1,3,224,224] ImageNet-normalized input, [1,1000] logits)
and gives real, camera-driven descriptions today.

Run (from repo root):
    ml/teamb/.venv/bin/python ml/teamb/export_classifier_xnnpack.py \
        --out-dir android/app/src/main/assets

Outputs: classifier.pte + labels.txt
"""

from __future__ import annotations

import argparse
import os

import torch
from torch.export import export
from torchvision.models import MobileNet_V3_Small_Weights, mobilenet_v3_small

from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
from executorch.exir import to_edge_transform_and_lower


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default="android/app/src/main/assets")
    args = ap.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)

    weights = MobileNet_V3_Small_Weights.DEFAULT
    model = mobilenet_v3_small(weights=weights).eval()

    sample = (torch.randn(1, 3, 224, 224),)

    print("Exporting + lowering to XNNPACK...")
    exported = export(model, sample)
    et = to_edge_transform_and_lower(
        exported, partitioner=[XnnpackPartitioner()]
    ).to_executorch()

    pte_path = os.path.join(args.out_dir, "classifier.pte")
    with open(pte_path, "wb") as f:
        f.write(et.buffer)
    print(f"Wrote {pte_path} ({os.path.getsize(pte_path)} bytes)")

    labels = weights.meta["categories"]
    labels_path = os.path.join(args.out_dir, "labels.txt")
    with open(labels_path, "w") as f:
        f.write("\n".join(labels) + "\n")
    print(f"Wrote {labels_path} ({len(labels)} labels)")


if __name__ == "__main__":
    main()
