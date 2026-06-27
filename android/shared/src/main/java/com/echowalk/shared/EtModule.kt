package com.echowalk.shared

import java.nio.ByteBuffer

/**
 * Thin wrapper over the on-device inference runtime. ONE place where every team loads a model
 * and runs inference, so the QNN / NPU setup lives in a single spot.
 *
 * Backed today by [TfliteQnnModule] (TensorFlow Lite + Qualcomm QNN delegate on the Hexagon NPU).
 *
 * Keep the surface small; add typed input/output helpers as teams need them.
 */
interface EtModule {
    /**
     * Run a forward pass with a single float input tensor.
     * @param input flattened input data
     * @param inputShape e.g. intArrayOf(1, 518, 518, 3)
     * @return one float array per output tensor, in graph-declared order.
     *         Non-float outputs (e.g. uint8 class ids) are widened to float on the way out.
     */
    fun forward(input: FloatArray, inputShape: IntArray): Array<FloatArray>

    /**
     * Zero-copy variant: caller hands us a direct [ByteBuffer] already laid out in the model's
     * native input dtype/order. Faster than [forward] when frames are already in a direct buffer.
     */
    fun forward(input: ByteBuffer, inputShape: IntArray): Array<FloatArray> {
        // Default impl converts back; overrides should avoid the copy.
        val floats = FloatArray(input.asFloatBuffer().remaining())
        input.asFloatBuffer().get(floats)
        return forward(floats, inputShape)
    }

    fun close()
}
