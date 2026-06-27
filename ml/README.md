# EchoWalk — model export (ExecuTorch + Qualcomm QNN)

Each team exports its PyTorch model(s) to a `.pte` that runs on the Galaxy S25 Hexagon NPU via the
QNN delegate, then validates on-device before handing the `.pte` to the Android app.

## Layout

```
ml/
├── shared/export_utils.py   # common helpers (lower-to-QNN, save .pte, on-device sanity check)
├── teama/export_depth.py    # Depth-Anything-V2-Small  -> depth.pte
├── teama/export_yolo.py     # YOLO nano                -> yolo.pte
├── teamb/export_smolvlm.py  # SmolVLM-500M             -> vlm.pte (+ tags+Llama fallback)
└── teamc/export_clip.py     # CLIP/MobileCLIP encoder  -> place_encoder.pte
```

## Setup (do M0 first)

1. Set up ExecuTorch + the Qualcomm AI Engine Direct (QNN) backend. Follow:
   - https://pytorch.org/executorch/main/getting-started-setup
   - https://pytorch.org/executorch/main/backends-qualcomm
2. Install deps: `pip install -r requirements.txt` (plus the QNN SDK / QAIRT separately).
3. Set env: `QNN_SDK_ROOT`, `ANDROID_NDK_ROOT`, device `SERIAL_NUM`, `SOC_MODEL` for the S25.
4. Prefer the pre-exported QNN artifacts on Qualcomm AI Hub / Hugging Face when available — faster
   than compiling from scratch.

## Output

Drop validated `.pte` files into `android/app/src/main/assets/` (or push to `/data/local/tmp`
during development) and bundle the matching QNN `.so` libs in
`android/app/src/main/jniLibs/arm64-v8a/`.

> These are TEMPLATES with TODOs — each team fills in its own export logic.
