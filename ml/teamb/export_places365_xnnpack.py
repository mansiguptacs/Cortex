"""Export a Places365 *scene* classifier to a CPU/XNNPACK ExecuTorch .pte.

Unlike the ImageNet object hedge (`export_classifier_xnnpack.py`), Places365 predicts the *kind of
space* you're in -- "office", "corridor", "kitchen", "staircase", "bedroom" -- which maps directly
onto EchoWalk's navigation pitch and demos far better than ImageNet's dog-breeds/kitchenware classes.

Same runtime contract as the object classifier (drops into `ClassifierSceneDescriber`):
  - input  [1,3,224,224], ImageNet mean/std
  - output [1,365] logits
Additionally writes `classifier_kind.txt` = "scene" so the app narrates "You appear to be in ..."
instead of "I see ...".

Weights: ResNet18 trained on Places365 by CSAILVision (~46MB).

Run (from repo root, reusing the existing venv):
    ml/teamb/.venv/bin/python ml/teamb/export_places365_xnnpack.py \
        --out-dir android/app/src/main/assets

Outputs: classifier.pte + labels.txt + classifier_kind.txt
"""

from __future__ import annotations

import argparse
import os
import urllib.request

import torch
from torch.export import export
from torchvision import models

from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
from executorch.exir import to_edge_transform_and_lower

WEIGTHS_URL = "http://places2.csail.mit.edu/models_places365/resnet18_places365.pth.tar"
CATEGORIES_URL = (
    "https://raw.githubusercontent.com/CSAILVision/places365/master/categories_places365.txt"
)


def _download(url: str, dest: str) -> str:
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print(f"Using cached {dest}")
        return dest
    print(f"Downloading {url} -> {dest}")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    urllib.request.urlretrieve(url, dest)
    print(f"  ({os.path.getsize(dest)} bytes)")
    return dest


def _clean_category(raw: str) -> str:
    """'/a/apartment_building/outdoor' -> 'apartment building outdoor'."""
    label = raw.strip().split(" ")[0]            # drop trailing index
    if label.startswith("/"):
        label = label[3:]                        # strip leading '/a/'
    return label.replace("/", " ").replace("_", " ").strip()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default="android/app/src/main/assets")
    ap.add_argument("--cache-dir", default="ml/teamb/.cache")
    args = ap.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)

    weights_path = _download(
        WEIGTHS_URL, os.path.join(args.cache_dir, "resnet18_places365.pth.tar")
    )
    categories_path = _download(
        CATEGORIES_URL, os.path.join(args.cache_dir, "categories_places365.txt")
    )

    model = models.resnet18(num_classes=365)
    checkpoint = torch.load(weights_path, map_location="cpu", weights_only=False)
    state_dict = {k.replace("module.", ""): v for k, v in checkpoint["state_dict"].items()}
    model.load_state_dict(state_dict)
    model.eval()

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

    with open(categories_path) as f:
        labels = [_clean_category(line) for line in f if line.strip()]
    assert len(labels) == 365, f"expected 365 Places365 categories, got {len(labels)}"

    labels_path = os.path.join(args.out_dir, "labels.txt")
    with open(labels_path, "w") as f:
        f.write("\n".join(labels) + "\n")
    print(f"Wrote {labels_path} ({len(labels)} labels)")

    kind_path = os.path.join(args.out_dir, "classifier_kind.txt")
    with open(kind_path, "w") as f:
        f.write("scene\n")
    print(f"Wrote {kind_path} (scene)")


if __name__ == "__main__":
    main()
