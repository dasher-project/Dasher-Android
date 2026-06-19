package at.dasher.android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Copies the bundled DasherCore `Data/` tree from the APK's assets into
 * [Context.getFilesDir], so that `dasher_create(dataDir, …)` receives real
 * filesystem paths (the C API does not read Android `AssetManager` directly).
 *
 * The Gradle `assets.srcDir "../third_party/DasherCore/Data"` directive puts
 * every alphabet / colour / training / control file at the asset root, so we
 * recursively copy the whole asset tree.
 *
 * A versioned sentinel file (`dasher_data_v<DATA_VERSION>`) short-circuits the
 * copy on subsequent launches. Bump [DATA_VERSION] when the bundled data changes
 * to force a refresh.
 *
 * Idempotent, safe to call on the main thread (small tree, ~hundreds of tiny
 * files) but intended to be invoked from a background coroutine.
 */
object DataInstaller {
    private const val TAG = "DataInstaller"

    /**
     * Bump when the bundled DasherCore/Data contents change to force re-extraction.
     * DasherCore pins the submodule, so this is manual: update when bumping the pin.
     */
    private const val DATA_VERSION = 1

    /**
     * Ensures assets are extracted into [outDir] (normally `context.filesDir`).
     *
     * @return the absolute path of [outDir], suitable for passing as `data_dir` /
     *   `user_dir` to [NativeBridge.nativeCreate].
     */
    fun ensureInstalled(context: Context, outDir: File = File(context.filesDir, "dasher_data")): String {
        val marker = File(outDir, ".installed_v$DATA_VERSION")
        if (marker.exists()) {
            Log.d(TAG, "Data already installed at ${outDir.absolutePath}")
            return outDir.absolutePath
        }

        if (outDir.exists()) {
            // Previous version present — clear it so we don't mix old/new files.
            outDir.deleteRecursively()
        }
        outDir.mkdirs()

        val assetManager = context.assets
        copyAssetDir(assetManager, "", outDir)

        marker.writeText("DasherCore data version $DATA_VERSION")
        Log.i(TAG, "Installed Dasher data to ${outDir.absolutePath}")
        return outDir.absolutePath
    }

    private fun copyAssetDir(assetManager: android.content.res.AssetManager,
                             assetPath: String, outDir: File) {
        val entries = assetManager.list(assetPath) ?: return
        if (entries.isEmpty()) {
            // `list` returns an empty array for files (and missing dirs).
            return
        }
        for (entry in entries) {
            val childAssetPath = if (assetPath.isEmpty()) entry else "$assetPath/$entry"
            val childOut = File(outDir, entry)

            val subEntries = assetManager.list(childAssetPath) ?: emptyArray()
            if (subEntries.isEmpty()) {
                // It's a file — copy it.
                copyAssetFile(assetManager, childAssetPath, childOut)
            } else {
                // It's a directory — recurse.
                childOut.mkdirs()
                copyAssetDir(assetManager, childAssetPath, childOut)
            }
        }
    }

    private fun copyAssetFile(assetManager: android.content.res.AssetManager,
                              assetPath: String, outFile: File) {
        assetManager.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
    }
}
