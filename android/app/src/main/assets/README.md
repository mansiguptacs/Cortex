# assets/

Drop exported on-device model files here (git-ignored — distribute out-of-band):

- `depth_anything_v2.tflite` (Team A, TFLite + QNN delegate)
- `yolov10_det.tflite` (Team A)
- `coco.names` (Team A — YOLO class labels, committed because it's tiny)
- VLM artifacts (Team B)
- `place_encoder.tflite` (Team C)

Run `python ml/teama/export_depth.py` and `python ml/teama/export_yolo.py` to (re)generate.
