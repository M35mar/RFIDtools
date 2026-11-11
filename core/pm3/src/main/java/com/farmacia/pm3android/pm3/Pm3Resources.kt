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
import java.io.IOException

object Pm3Resources {

    private const val TAG = "pm3-resources"
    // Staging and backup trees live inside .proxmark3: same volume, so the
    // install can swap directories with a rename instead of deleting files.
    private const val STAGING_DIRECTORY = ".staging"
    private const val BACKUP_SUFFIX = ".old"

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
        // Stage the whole bundle first: a failed copy leaves the installed
        // tree untouched, and only complete directories are ever swapped.
        val staging = File(target, STAGING_DIRECTORY)
        try {
            staging.deleteRecursively()
            if (!staging.mkdirs()) {
                throw IOException("cannot create ${staging.path}")
            }
            for (dir in DIRECTORIES) {
                val staged = File(staging, dir)
                staged.mkdirs()
                copyAssetDirectory(assets, dir, staged)
            }
            for (dir in DIRECTORIES) {
                swapDirectory(File(target, dir), File(staging, dir))
            }
        } finally {
            staging.deleteRecursively()
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

    /**
     * Replaces [destination] with the staged copy in one rename. Entries that
     * exist only in the destination are moved back afterwards, so files the
     * user added inside a bundled tree survive an upgrade.
     */
    private fun swapDirectory(destination: File, staged: File) {
        val parent = destination.parentFile ?: return
        val backup = File(parent, destination.name + BACKUP_SUFFIX)
        // A previous run may have died between the two renames, or while
        // restoring user entries: finish that before the backup is recycled.
        if (backup.isDirectory) {
            if (destination.exists()) {
                runCatching { restoreUserEntries(backup, destination) }
            } else {
                runCatching { backup.renameTo(destination) }
            }
        }
        backup.deleteRecursively()
        val hadDestination = destination.exists()
        if (hadDestination && !destination.renameTo(backup)) {
            // Some volumes refuse renames: merge without deleting anything.
            mergeDirectory(staged, destination)
            staged.deleteRecursively()
            return
        }
        if (!staged.renameTo(destination)) {
            // Put the previous tree back: a failed install must be a no-op.
            if (hadDestination) {
                runCatching { backup.renameTo(destination) }
            }
            throw IOException("cannot install ${destination.name}")
        }
        if (hadDestination) {
            restoreUserEntries(backup, destination)
            backup.deleteRecursively()
        }
    }

    /** Moves entries that exist only in [backup] back into [destination]. */
    private fun restoreUserEntries(backup: File, destination: File) {
        for (entry in backup.listFiles() ?: return) {
            val restored = File(destination, entry.name)
            if (!restored.exists()) {
                if (!entry.renameTo(restored)) {
                    entry.copyRecursively(restored, overwrite = false)
                }
                continue
            }
            if (entry.isDirectory && restored.isDirectory) {
                restoreUserEntries(entry, restored)
            }
        }
    }

    /** Fallback for volumes without rename: overwrite bundled files only. */
    private fun mergeDirectory(source: File, destination: File) {
        destination.mkdirs()
        for (entry in source.listFiles() ?: return) {
            val target = File(destination, entry.name)
            if (entry.isDirectory) {
                mergeDirectory(entry, target)
            } else {
                entry.copyTo(target, overwrite = true)
            }
        }
    }
}
