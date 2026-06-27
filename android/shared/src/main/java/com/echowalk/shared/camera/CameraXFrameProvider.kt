package com.echowalk.shared.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.echowalk.shared.Frame
import com.echowalk.shared.FrameProvider
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * The one and only owner of the CameraX session. Publishes upright RGB [Frame]s to subscribers
 * and keeps the latest one available for on-demand consumers (Teams B & C).
 */
class CameraXFrameProvider(
    private val context: Context,
) : FrameProvider {

    private val listeners = CopyOnWriteArraySet<(Frame) -> Unit>()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var lastFrame: Frame? = null

    override fun latest(): Frame? = lastFrame

    override fun subscribe(listener: (Frame) -> Unit) {
        listeners.add(listener)
    }

    override fun unsubscribe(listener: (Frame) -> Unit) {
        listeners.remove(listener)
    }

    /** Bind preview + analysis to the lifecycle. Call once from the hosting Activity. */
    fun bind(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::onFrame) }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }, ContextCompat.getMainExecutor(context))
    }

    private fun onFrame(image: ImageProxy) {
        try {
            val bitmap = image.toUprightBitmap()
            val frame = Frame(
                rgb = bitmap,
                width = bitmap.width,
                height = bitmap.height,
                tsMs = System.currentTimeMillis(),
            )
            lastFrame = frame
            listeners.forEach { it(frame) }
        } finally {
            image.close()
        }
    }

    fun shutdown() {
        analysisExecutor.shutdown()
        listeners.clear()
    }
}

/** Convert an [ImageProxy] to an upright RGB [Bitmap], applying the sensor rotation. */
private fun ImageProxy.toUprightBitmap(): Bitmap {
    val raw = toBitmap() // CameraX 1.3+ helper
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return raw
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
}
