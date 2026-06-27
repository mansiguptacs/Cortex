package com.echowalk.shared

import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

/**
 * Default [EtModule] backed by the ExecuTorch Android runtime.
 *
 * Loads a `.pte` (built with the QNN delegate so it runs on the Hexagon NPU). The QNN native
 * libraries must be bundled in `app/src/main/jniLibs/arm64-v8a/` and matched to the QNN SDK
 * version used at export time.
 *
 * NOTE: the ExecuTorch Java API evolves between releases — if this doesn't compile, align the
 * `org.pytorch:executorch-android` version in build.gradle with these calls.
 */
class ExecuTorchModule private constructor(
    private val module: Module,
) : EtModule {

    override fun forward(input: FloatArray, inputShape: IntArray): Array<FloatArray> {
        val shape = LongArray(inputShape.size) { inputShape[it].toLong() }
        val tensor: Tensor = Tensor.fromBlob(input, shape)
        val outputs: Array<EValue> = module.forward(EValue.from(tensor))
        return Array(outputs.size) { i -> outputs[i].toTensor().dataAsFloatArray }
    }

    override fun close() {
        // module.destroy() // uncomment if available in your runtime version
    }

    companion object {
        /** @param path absolute path to a `.pte` file (e.g. copied from assets to filesDir). */
        fun load(path: String): ExecuTorchModule = ExecuTorchModule(Module.load(path))
    }
}
