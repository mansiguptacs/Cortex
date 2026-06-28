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

    /** True when [assetName] exists in the APK assets (does not stage). */
    fun has(context: Context, assetName: String): Boolean =
        try {
            context.assets.open(assetName).close()
            true
        } catch (_: FileNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }

    /** True when every name in [assetNames] is bundled. */
    fun hasAll(context: Context, assetNames: Collection<String>): Boolean =
        assetNames.all { has(context, it) }

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

    /**
     * Stage every file under assets/[assetDir]/ into `filesDir/[assetDir]/`.
     * Returns the absolute directory path, or null if the asset folder is missing.
     */
    fun ensureDir(context: Context, assetDir: String): String? {
        val names = try {
            context.assets.list(assetDir)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        if (names.isEmpty()) {
            Log.w(TAG, "Asset dir '$assetDir/' not bundled")
            return null
        }
        val outDir = File(context.filesDir, assetDir)
        if (!outDir.exists() && !outDir.mkdirs()) {
            Log.e(TAG, "Failed to create dir ${outDir.absolutePath}")
            return null
        }
        return try {
            for (name in names) {
                val rel = "$assetDir/$name"
                val outFile = File(outDir, name)
                context.assets.open(rel).use { input ->
                    if (!outFile.exists() || outFile.length() == 0L) {
                        outFile.outputStream().use { input.copyTo(it) }
                    }
                }
            }
            Log.i(TAG, "Staged '$assetDir/' -> ${outDir.absolutePath} (${names.size} files)")
            outDir.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stage asset dir '$assetDir/'", e)
            null
        }
    }
}
