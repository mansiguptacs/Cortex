"""Shared model-export helpers for EchoWalk ML scripts."""

from __future__ import annotations

import hashlib
import os
import shutil
from pathlib import Path

# Stable handoff names for Team B SmolVLM (see ml/teamb/EXPORT_STRATEGY.md).
SMOLVLM_PTE_NAMES = (
    "vlm_encoder.pte",
    "vlm_text_embedding.pte",
    "vlm_decoder.pte",
)

# Loose glob-style aliases when copying from ExecuTorch artifact dirs.
SMOLVLM_PTE_ALIASES = {
    "vlm_encoder.pte": ("encoder", "vision"),
    "vlm_text_embedding.pte": ("text_embedding", "text-embedding", "embedding"),
    "vlm_decoder.pte": ("decoder", "llama", "kv_llama"),
}


def lower_to_qnn(model, example_inputs, *, quant_dtype="int8", soc_model=None):
    """Lower a PyTorch model to a QNN-delegated ExecuTorch program.

    Requires a full ExecuTorch+QNN build host. Use llama.py for SmolVLM instead
    (see ml/teamb/EXPORT_STRATEGY.md).
    """
    raise NotImplementedError(
        "Full QNN lowering is not implemented in this repo. "
        "Compile on a QNN host via executorch/.../llama/llama.py, then pass "
        "--prebuilt-dir to ml/teamb/export_smolvlm.py."
    )


def save_pte(program, out_path: str) -> str:
    """Serialize an ExecuTorch program to a .pte file."""
    out = Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    if hasattr(program, "buffer"):
        data = program.buffer()
    elif isinstance(program, (bytes, bytearray)):
        data = bytes(program)
    else:
        raise TypeError(f"Unsupported program type for save_pte: {type(program)!r}")
    out.write_bytes(data)
    return str(out.resolve())


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _match_pte(source: Path, aliases: tuple[str, ...]) -> bool:
    name = source.name.lower()
    return any(alias in name for alias in aliases)


def normalize_smolvlm_ptes(source_dir: Path, dest_dir: Path) -> list[Path]:
    """Copy/rename SmolVLM .pte artifacts into stable handoff filenames."""
    source_dir = source_dir.resolve()
    dest_dir = dest_dir.resolve()
    dest_dir.mkdir(parents=True, exist_ok=True)

    pte_files = sorted(source_dir.glob("*.pte"))
    if not pte_files:
        raise FileNotFoundError(f"No .pte files found in {source_dir}")

    copied: list[Path] = []
    used_sources: set[Path] = set()

    for dest_name, aliases in SMOLVLM_PTE_ALIASES.items():
        dest = dest_dir / dest_name
        if dest.exists() and dest.resolve().parent == source_dir.resolve():
            copied.append(dest)
            continue

        match = next(
            (
                candidate
                for candidate in pte_files
                if candidate not in used_sources and _match_pte(candidate, aliases)
            ),
            None,
        )
        if match is None:
            continue
        shutil.copy2(match, dest)
        used_sources.add(match)
        copied.append(dest)

    if len(copied) == len(SMOLVLM_PTE_NAMES):
        return copied

    # Fallback: if exactly three PTEs with no keyword match, map sorted -> stable names.
    if len(pte_files) == len(SMOLVLM_PTE_NAMES):
        for src, dest_name in zip(pte_files, SMOLVLM_PTE_NAMES):
            dest = dest_dir / dest_name
            if not dest.exists() or dest.stat().st_size == 0:
                shutil.copy2(src, dest)
            copied.append(dest)
        return copied

    missing = set(SMOLVLM_PTE_NAMES) - {p.name for p in copied}
    raise FileNotFoundError(
        f"Could not normalize SmolVLM PTEs in {source_dir}. Missing: {sorted(missing)}. "
        f"Found: {[p.name for p in pte_files]}"
    )


def copy_tokenizer(source_dir: Path, dest_dir: Path) -> Path:
    """Copy a Hugging Face tokenizer directory into dest_dir/tokenizer/."""
    source_dir = source_dir.resolve()
    dest = (dest_dir / "tokenizer").resolve()
    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(source_dir, dest)
    return dest


def sanity_check_on_device(pte_path: str, example_inputs, *, serial=None) -> None:
    """Push + run the .pte on the device via SimpleADB.

    Udit owns S25 validation; this remains a stub for optional local adb checks.
    """
    if not os.environ.get("SERIAL_NUM") and serial is None:
        raise NotImplementedError(
            "On-device sanity check requires SERIAL_NUM and a QNN host with SimpleADB. "
            "Hand off to Udit for S25 validation."
        )
    raise NotImplementedError(
        "Use executorch SimpleADB or TeamBHarnessActivity on the S25 (Udit)."
    )
