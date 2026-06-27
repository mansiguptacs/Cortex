package com.echowalk.shared

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

/**
 * Stages bundled model assets onto the real filesystem.
 *
 * ExecuTorch's `Module.load` needs an absolute file path, but `.pte` files ship inside the APK's
 * assets (compressed/virtual). This copies an asset into `filesDir` once and hands back the path.
 *
 * Returns `null` when the asset isn't bundled, so callers can gracefully fall back to a stub.
 */
object AssetModels {
    private const val TAG = "AssetModels"

    fun ensure(context: Context, assetName: String): String? {
        val outFile = File(context.filesDir, assetName)
        return try {
            context.assets.open(assetName).use { input ->
                if (!outFile.exists() || outFile.length() == 0L) {
                    outFile.outputStream().use { input.copyTo(it) }
                    Log.i(TAG, "Staged '$assetName' -> ${outFile.absolutePath} (${outFile.length()} B)")
                }
            }
            outFile.absolutePath
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Asset '$assetName' not bundled; caller should fall back")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stage asset '$assetName'", e)
            null
        }
    }
}
