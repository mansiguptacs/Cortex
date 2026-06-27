package com.echowalk.shared

import android.content.Context
import android.util.Log
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Default [EtModule] backed by TensorFlow Lite with the Qualcomm QNN delegate routing the graph
 * onto the Hexagon NPU (HTP) of the Snapdragon 8 Elite. CPU fallback is allowed for unsupported
 * ops but every op of our two production models (Depth-Anything-V2-Small, YOLOv10-Det) is QNN-
 * supported, so we expect 100% HTP execution.
 *
 * Native libs required at runtime (vendored in `:app/src/main/jniLibs/arm64-v8a/`):
 *   libQnnTFLiteDelegate.so, libqnn_delegate_jni.so   (from the qtld AAR)
 *   libQnnHtp.so, libQnnHtpV79Stub.so, libQnnSystem.so, libQnnHtpV79Skel.so (QAIRT SDK)
 *
 * Multiple outputs are returned in graph-declared order. Non-float outputs (uint8 / int32) are
 * widened to float on the way out so callers can stay tensor-agnostic.
 */
class TfliteQnnModule private constructor(
    private val interpreter: Interpreter,
    private val delegate: QnnDelegate?,
) : EtModule {

    private val outputBuffers: Array<ByteBuffer>

    init {
        outputBuffers = Array(interpreter.outputTensorCount) { i ->
            val t = interpreter.getOutputTensor(i)
            ByteBuffer.allocateDirect(t.numBytes()).order(ByteOrder.nativeOrder())
        }
    }

    override fun forward(input: FloatArray, inputShape: IntArray): Array<FloatArray> {
        val inBytes = input.size * 4
        val buf = ByteBuffer.allocateDirect(inBytes).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(input)
        buf.rewind()
        return forward(buf, inputShape)
    }

    override fun forward(input: ByteBuffer, inputShape: IntArray): Array<FloatArray> {
        // Resize input tensor if needed
        val inTensor = interpreter.getInputTensor(0)
        if (!inTensor.shape().contentEquals(inputShape)) {
            interpreter.resizeInput(0, inputShape)
            interpreter.allocateTensors()
        }
        outputBuffers.forEach { it.rewind() }
        val outMap = HashMap<Int, Any>(outputBuffers.size)
        for (i in outputBuffers.indices) outMap[i] = outputBuffers[i]
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outMap)

        return Array(outputBuffers.size) { i ->
            val tensor = interpreter.getOutputTensor(i)
            val buf = outputBuffers[i].duplicate().order(ByteOrder.nativeOrder())
            buf.rewind()
            when (tensor.dataType()) {
                DataType.FLOAT32 -> FloatArray(tensor.numElements()).also { buf.asFloatBuffer().get(it) }
                DataType.UINT8 -> FloatArray(tensor.numElements()).also { arr ->
                    val b = ByteArray(tensor.numElements())
                    buf.get(b)
                    for (k in arr.indices) arr[k] = (b[k].toInt() and 0xFF).toFloat()
                }
                DataType.INT32 -> FloatArray(tensor.numElements()).also { arr ->
                    val ib = IntArray(tensor.numElements())
                    buf.asIntBuffer().get(ib)
                    for (k in arr.indices) arr[k] = ib[k].toFloat()
                }
                else -> error("Unsupported output dtype ${tensor.dataType()} for tensor $i")
            }
        }
    }

    override fun close() {
        try {
            interpreter.close()
        } catch (t: Throwable) {
            Log.w(TAG, "interpreter.close() failed", t)
        }
        try {
            delegate?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "delegate.close() failed", t)
        }
    }

    companion object {
        private const val TAG = "TfliteQnnModule"

        /**
         * Sensible defaults for our use case: HTP backend, high-performance, fp16 precision.
         * The skel library dir must point at the dir containing `libQnnHtpV79Skel.so` so the
         * Hexagon DSP can load it via FastRPC. The APK's native lib dir already contains it.
         */
        private fun defaultOptions(skelDir: String?): QnnDelegate.Options =
            QnnDelegate.Options().apply {
                setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_BURST)
                setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16)
                setLogLevel(QnnDelegate.Options.LogLevel.LOG_LEVEL_WARN)
                if (skelDir != null) {
                    setSkelLibraryDir(skelDir)
                }
            }

        /**
         * Load a `.tflite` from an absolute filesystem path (e.g. copied out of assets to
         * filesDir) and bind the QNN delegate. Falls back to CPU if the QNN delegate fails to
         * initialize, after logging — we'd rather have a slow demo than a crash.
         *
         * @param skelLibraryDir directory containing libQnnHtpV79Skel.so. On Android this is
         *   typically `context.applicationInfo.nativeLibraryDir`. Pass null to skip and let
         *   QNN search default paths (will likely fail on stock Android).
         */
        fun load(path: String, numThreads: Int = 2, skelLibraryDir: String? = null): TfliteQnnModule {
            val buffer = mapModel(path)
            val options = Interpreter.Options().apply {
                setNumThreads(numThreads)
                setUseXNNPACK(true)
            }
            val delegate: QnnDelegate? = try {
                QnnDelegate(defaultOptions(skelLibraryDir)).also { options.addDelegate(it) }
            } catch (t: Throwable) {
                Log.w(TAG, "QNN delegate init failed; running on CPU/XNNPACK", t)
                null
            }
            val interpreter = try {
                Interpreter(buffer, options)
            } catch (t: Throwable) {
                // The delegate may pass init but fail at graph-apply time. Retry CPU-only.
                Log.w(TAG, "Interpreter ctor with QNN delegate threw; retrying on CPU", t)
                try { delegate?.close() } catch (_: Throwable) {}
                val cpuOpts = Interpreter.Options().apply { setNumThreads(numThreads); setUseXNNPACK(true) }
                Interpreter(buffer, cpuOpts).also {
                    Log.i(TAG, "Loaded $path on CPU fallback inputs=${it.inputTensorCount} outputs=${it.outputTensorCount}")
                    return TfliteQnnModule(it, null)
                }
            }
            Log.i(TAG, "Loaded $path  inputs=${interpreter.inputTensorCount} outputs=${interpreter.outputTensorCount} qnn=${delegate != null}")
            return TfliteQnnModule(interpreter, delegate)
        }

        /** Convenience: copy the named asset to filesDir if needed, then [load] it. */
        fun loadAsset(context: Context, assetName: String, numThreads: Int = 2): TfliteQnnModule {
            val out = File(context.filesDir, assetName)
            if (!out.exists() || out.length() == 0L) {
                context.assets.open(assetName).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            return load(out.absolutePath, numThreads, context.applicationInfo.nativeLibraryDir)
        }

        private fun mapModel(path: String): MappedByteBuffer {
            FileInputStream(path).use { fis ->
                return fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, fis.channel.size())
            }
        }
    }
}
