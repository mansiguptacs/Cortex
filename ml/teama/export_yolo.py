"""Team A — export YOLOv10-Det to a TFLite model for the QNN delegate.

See export_depth.py for the rationale (Path A: TFLite + QNN delegate, no ExecuTorch).

Output: yolov10_det.tflite + labels.txt -> android/app/src/main/assets/

The model emits already-decoded boxes (pixel-space, 640x640 input), per-anchor scores, and
class ids (uint8). 8400 anchor outputs; apply a confidence threshold downstream.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "ml" / "teama" / "_artifacts" / "yolo_tflite"
ANDROID_ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets"
DEVICE = "Samsung Galaxy S25 Ultra"


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    cmd = [
        sys.executable, "-m", "qai_hub_models.models.yolov10_det.export",
        "--target-runtime", "tflite",
        "--device", DEVICE,
        "--skip-profiling", "--skip-inferencing", "--skip-summary",
        "--output-dir", str(OUT_DIR),
    ]
    print("$", " ".join(cmd))
    rc = subprocess.call(cmd)
    if rc != 0:
        return rc
    tflite_files = list(OUT_DIR.rglob("*.tflite"))
    if not tflite_files:
        print("ERROR: no .tflite produced under", OUT_DIR)
        return 2
    ANDROID_ASSETS.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(tflite_files[0], ANDROID_ASSETS / "yolov10_det.tflite")
    print("staged model ->", ANDROID_ASSETS / "yolov10_det.tflite")
    label_files = list(OUT_DIR.rglob("labels.txt"))
    if label_files:
        shutil.copyfile(label_files[0], ANDROID_ASSETS / "coco.names")
        print("staged labels ->", ANDROID_ASSETS / "coco.names")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
