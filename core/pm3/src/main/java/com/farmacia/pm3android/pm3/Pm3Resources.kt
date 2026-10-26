// Installs the client-side resources (dictionaries, lua libs/scripts, cmd
// scripts, Python scripts, data resources) from the APK assets into
// $HOME/.proxmark3, the location the client builds from include/common.h looks
// up first on Android.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

import android.content.Context
import android.util.Log
import com.farmacia.pm3android.BuildConfig
import com.farmacia.pm3android.data.Pm3ScriptStore
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

object Pm3Resources {

    private const val TAG = "pm3-resources"

    // Reinstall on every app version bump: the bundled client resource set
    // can change when the proxmark3 submodule is updated.
    private val STAMP = "pm3-android-resources-${BuildConfig.VERSION_CODE}"

    private val DIRECTORIES = listOf(
        "dictionaries",
        "lualibs",
        "luascripts",
        "cmdscripts",
        "pyscripts",
        "resources",
    )

    /** $HOME for the client process space (set by Pm3Native.nativeInit). */
    fun home(context: Context): File {
        return context.filesDir
    }

    private fun userDirectory(context: Context): File {
        return File(home(context), ".proxmark3")
    }

    /**
     * Copies the packaged resource directories into $HOME/.proxmark3, unless
     * the stamp says they already match. tracing/log dirs are created as empty
     * trees because the client opens them lazily but never mkdirs.
     */
    @Synchronized
    fun ensure(context: Context) {
        val target = userDirectory(context)
        val stamp = File(target, ".asset-stamp")
        if (stamp.isFile && stamp.readText() == STAMP) {
            return
        }
        val assets = context.assets
        target.mkdirs()
        for (dir in DIRECTORIES) {
            val destination = File(target, dir)
            destination.deleteRecursively()
            destination.mkdirs()
            copyAssetDirectory(assets, dir, destination)
        }
        for (dir in listOf("traces", "logs")) {
            File(target, dir).mkdirs()
        }
        // Reinstall user scripts after the bundled trees have been replaced.
        // The originals live in user-scripts/ and survive reset().
        Pm3ScriptStore.installAll(context)
        stamp.writeText(STAMP)
        Log.i(TAG, "client resources installed in ${target.path}")
    }

    @Synchronized
    fun isInstalled(context: Context): Boolean {
        val stamp = File(userDirectory(context), ".asset-stamp")
        return stamp.isFile && runCatching { stamp.readText() == STAMP }.getOrDefault(false)
    }

    /** Removes the installed resource trees but keeps logs, traces and prefs. */
    @Synchronized
    fun reset(context: Context) {
        val target = userDirectory(context)
        for (dir in DIRECTORIES) {
            File(target, dir).deleteRecursively()
        }
        File(target, ".asset-stamp").delete()
        Log.i(TAG, "client resources reset in ${target.path}")
    }

    private fun copyAssetDirectory(
        assets: android.content.res.AssetManager,
        assetPath: String,
        destination: File,
    ) {
        for (entry in assets.list(assetPath) ?: return) {
            val entryPath = if (assetPath.isEmpty()) entry else "$assetPath/$entry"
            val output = File(destination, entry)
            val children = assets.list(entryPath)
            val isDirectory = entry.endsWith("/") || (children?.isNotEmpty() == true)
            if (isDirectory) {
                output.mkdirs()
                copyAssetDirectory(assets, entryPath, output)
            } else {
                try {
                    copyAssetFile(assets, entryPath, output)
                } catch (_: FileNotFoundException) {
                    // AssetManager cannot distinguish an empty directory from
                    // a file through list(); create it and recurse.
                    output.mkdirs()
                    copyAssetDirectory(assets, entryPath, output)
                }
            }
        }
    }

    private fun copyAssetFile(
        assets: android.content.res.AssetManager,
        assetPath: String,
        output: File,
    ) {
        assets.open(assetPath).use { input ->
            FileOutputStream(output).use { fos ->
                input.copyTo(fos)
            }
        }
    }
}
