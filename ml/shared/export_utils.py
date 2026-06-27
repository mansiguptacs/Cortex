"""Shared model-export helpers (TEMPLATE).

One place for the lower-to-QNN + save-.pte + on-device sanity-check flow so every team's export
script looks the same. Fill in against your confirmed ExecuTorch + QNN versions (M0).
"""

from __future__ import annotations


def lower_to_qnn(model, example_inputs, *, quant_dtype="int8", soc_model=None):
    """Lower a PyTorch model to a QNN-delegated ExecuTorch program.

    TODO: implement using executorch.backends.qualcomm (QnnPartitioner / to_edge / to_backend).
    See executorch/examples/qualcomm/oss_scripts for reference.
    """
    raise NotImplementedError("TODO: lower to QNN backend")


def save_pte(program, out_path: str) -> str:
    """Serialize an ExecuTorch program to a .pte file. TODO: implement."""
    raise NotImplementedError("TODO: write .pte")


def sanity_check_on_device(pte_path: str, example_inputs, *, serial=None) -> None:
    """Push + run the .pte on the device via SimpleADB and print latency / output.

    TODO: implement using executorch.examples.qualcomm.utils.SimpleADB.
    """
    raise NotImplementedError("TODO: on-device sanity check")
