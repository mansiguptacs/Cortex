"""Team B — export SmolVLM-500M to a QNN HTP .pte (TEMPLATE).

Meta has validated the VLM flow on the Galaxy S25. Reference:
executorch/examples/qualcomm/oss_scripts/llama (VLM support).
Fallback: YOLO/MobileNet tags + on-device Llama-3.2-1B.

Output: vlm.pte (+ tokenizer) -> android assets.

TODO(Team B): export vision encoder + text decoder, lower to QNN HTP, save, sanity-check.
"""

if __name__ == "__main__":
    raise SystemExit("TODO(Team B): implement SmolVLM export")
