package com.echowalk.shared

/**
 * Thin wrapper over org.pytorch:executorch-android. ONE place where every team loads a `.pte`
 * and runs inference, so the QNN delegate / native-lib setup lives in a single spot.
 *
 * Keep the surface small; add typed input/output helpers as teams need them.
 */
interface EtModule {
    /**
     * Run a forward pass with a single float tensor input.
     * @param input flattened input data
     * @param inputShape e.g. intArrayOf(1, 3, 224, 224)
     * @return one float array per output tensor
     */
    fun forward(input: FloatArray, inputShape: IntArray): Array<FloatArray>

    fun close()
}
