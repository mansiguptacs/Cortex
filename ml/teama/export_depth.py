"""Team A — export Depth-Anything-V2 to a TFLite model that runs on the QNN delegate.

We pick the AI Hub recipe (already tuned for Snapdragon 8 Elite). Path A in the plan:
TFLite + QNN delegate on Hexagon NPU. No ExecuTorch needed.

Output: depth_anything_v2.tflite -> android/app/src/main/assets/

Re-run if AI Hub updates QAIRT / the model.

Run:
    python -m qai_hub_models.models.depth_anything_v2.export \
        --target-runtime tflite \
        --device "Samsung Galaxy S25 Ultra" \
        --skip-profiling --skip-inferencing --skip-summary \
        --output-dir ml/teama/_artifacts/depth_tflite

This script is a thin wrapper around that invocation so the team has a single command
matching its build artifacts.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "ml" / "teama" / "_artifacts" / "depth_tflite"
ANDROID_ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets"
DEVICE = "Samsung Galaxy S25 Ultra"


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    cmd = [
        sys.executable, "-m", "qai_hub_models.models.depth_anything_v2.export",
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
    src = tflite_files[0]
    ANDROID_ASSETS.mkdir(parents=True, exist_ok=True)
    dst = ANDROID_ASSETS / "depth_anything_v2.tflite"
    shutil.copyfile(src, dst)
    print("staged", src, "->", dst)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
