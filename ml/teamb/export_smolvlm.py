#!/usr/bin/env python3
"""Team B — SmolVLM-500M handoff tooling for EchoWalk.

Primary job on a low-compute machine:
  1. Download tokenizer (Hugging Face)
  2. Normalize/copy QNN .pte artifacts into stable names
  3. Package a zip for Udit → android/app/src/main/assets/

Full QNN AOT compile is documented in EXPORT_STRATEGY.md (llama.py on SM8750 host).
"""

from __future__ import annotations

import argparse
import shutil
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from ml.shared.export_utils import (  # noqa: E402
    SMOLVLM_PTE_NAMES,
    copy_tokenizer,
    normalize_smolvlm_ptes,
    sha256_file,
)

DEFAULT_MODEL_ID = "HuggingFaceTB/SmolVLM-500M-Instruct"
DEFAULT_MODELS_DIR = Path("ml/teamb/models")
DEFAULT_EXPORTS_DIR = Path("ml/teamb/exports")
DEFAULT_HANDOFF_DIR = Path("ml/teamb/handoff")


def download_tokenizer(model_id: str, dest_dir: Path) -> Path:
    try:
        from transformers import AutoTokenizer
    except ImportError as exc:
        raise SystemExit(
            "Install handoff deps: pip install -r ml/teamb/requirements-handoff.txt"
        ) from exc

    dest = dest_dir / "tokenizer"
    dest.mkdir(parents=True, exist_ok=True)
    print(f"Downloading tokenizer for {model_id} → {dest}")
    tokenizer = AutoTokenizer.from_pretrained(model_id)
    tokenizer.save_pretrained(dest)
    return dest


def package_handoff(out_dir: Path, zip_path: Path) -> Path:
    io_spec = Path(__file__).resolve().parent / "IO_SPEC.md"
    if io_spec.is_file():
        shutil.copy2(io_spec, out_dir / "IO_SPEC.md")

    zip_path.parent.mkdir(parents=True, exist_ok=True)
    if zip_path.exists():
        zip_path.unlink()

    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(out_dir.rglob("*")):
            if path.is_file():
                archive.write(path, arcname=str(path.relative_to(out_dir)))

    print(f"Created handoff zip: {zip_path} ({zip_path.stat().st_size // (1024 * 1024)} MB)")
    return zip_path


def print_summary(out_dir: Path, zip_path: Path | None) -> None:
    print("\n=== Team B handoff summary ===")
    print(f"Output directory: {out_dir.resolve()}")
    for name in SMOLVLM_PTE_NAMES:
        pte = out_dir / name
        if pte.exists():
            print(f"  {name}: {pte.stat().st_size // (1024 * 1024)} MB  sha256={sha256_file(pte)[:16]}…")
        else:
            print(f"  {name}: MISSING")

    tokenizer_dir = out_dir / "tokenizer"
    if tokenizer_dir.is_dir():
        file_count = sum(1 for _ in tokenizer_dir.rglob("*") if _.is_file())
        print(f"  tokenizer/: {file_count} files")
    else:
        print("  tokenizer/: MISSING")

    if zip_path is not None:
        print(f"Zip: {zip_path.resolve()}")
    print("\nNext (Udit): unzip → android/app/src/main/assets/ → SmolVlmSceneDescriber")
    print("See ml/teamb/IO_SPEC.md for tensor shapes and prompt template.\n")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="SmolVLM export / handoff for Team B")
    parser.add_argument(
        "--model-id",
        default=DEFAULT_MODEL_ID,
        help="Hugging Face model id for tokenizer download",
    )
    parser.add_argument(
        "--prebuilt-dir",
        type=Path,
        help="Directory containing SmolVLM .pte files (from QNN host or AI Hub)",
    )
    parser.add_argument(
        "--tokenizer-dir",
        type=Path,
        help="Existing tokenizer directory to copy (skip download)",
    )
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=DEFAULT_EXPORTS_DIR / "handoff-run",
        help="Normalized handoff output directory",
    )
    parser.add_argument(
        "--download-tokenizer",
        action="store_true",
        help="Download tokenizer to ml/teamb/models/tokenizer/",
    )
    parser.add_argument(
        "--models-dir",
        type=Path,
        default=DEFAULT_MODELS_DIR,
        help="Local models root for tokenizer download",
    )
    parser.add_argument(
        "--package",
        action="store_true",
        help="Create ml/teamb/handoff/vlm-handoff-<timestamp>.zip",
    )
    parser.add_argument(
        "--handoff-dir",
        type=Path,
        default=DEFAULT_HANDOFF_DIR,
        help="Directory for handoff zip files",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    if args.download_tokenizer:
        download_tokenizer(args.model_id, args.models_dir)
        if not args.prebuilt_dir:
            print("Tokenizer download complete.")
            return 0

    if args.prebuilt_dir is None:
        print(
            "Nothing to do. Examples:\n"
            "  python ml/teamb/export_smolvlm.py --download-tokenizer\n"
            "  python ml/teamb/export_smolvlm.py --prebuilt-dir ml/teamb/exports --package\n",
            file=sys.stderr,
        )
        return 1

    prebuilt = args.prebuilt_dir.resolve()
    if not prebuilt.is_dir():
        raise SystemExit(f"--prebuilt-dir not found: {prebuilt}")

    out_dir = args.out_dir.resolve()
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    normalize_smolvlm_ptes(prebuilt, out_dir)

    tokenizer_src = args.tokenizer_dir
    if tokenizer_src is None:
        default_tok = (args.models_dir / "tokenizer").resolve()
        if default_tok.is_dir():
            tokenizer_src = default_tok
    if tokenizer_src is not None:
        copy_tokenizer(tokenizer_src, out_dir)
    else:
        print(
            "Warning: no tokenizer found. Run with --download-tokenizer first.",
            file=sys.stderr,
        )

    zip_path = None
    if args.package:
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        zip_path = (args.handoff_dir / f"vlm-handoff-{stamp}.zip").resolve()
        package_handoff(out_dir, zip_path)

    print_summary(out_dir, zip_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
