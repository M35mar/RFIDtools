// Installs the client-side resources (dictionaries, lua libs/scripts, cmd
// scripts, data resources) from the APK assets into $HOME/.proxmark3, the
// location the client builds from include/common.h looks up first on Android.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object Pm3Resources {

    private const val TAG = "pm3-resources"

    // Bump when the bundled client resource set changes.
    private const val STAMP = "pm3-android-resources-2"

    private val DIRECTORIES = listOf(
        "dictionaries",
        "lualibs",
        "luascripts",
        "cmdscripts",
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
        stamp.writeText(STAMP)
        Log.i(TAG, "client resources installed in ${target.path}")
    }

    private fun copyAssetDirectory(
        assets: android.content.res.AssetManager,
        assetPath: String,
        destination: File,
    ) {
        for (entry in assets.list(assetPath) ?: return) {
            val entryPath = if (assetPath.isEmpty()) entry else "$assetPath/$entry"
            val output = File(destination, entry)
            val isDirectory = (assets.list(entryPath)?.isNotEmpty() ?: false) || entry.endsWith("/")
            if (isDirectory) {
                output.mkdirs()
                copyAssetDirectory(assets, entryPath, output)
            } else {
                copyAssetFile(assets, entryPath, output)
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
