// User-supplied scripts live outside the ephemeral client resource tree so a
// resource reset cannot delete them. They are mirrored into the directory the
// Proxmark3 client searches for the matching script type.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.data

import android.content.Context
import com.farmacia.pm3android.pm3.Pm3Resources
import java.io.File
import java.io.InputStream
import java.util.Locale

enum class ScriptLanguage(val extension: String) {
    LUA("lua"),
    CMD("cmd"),
    PYTHON("py"),
}

data class Pm3Script(
    val file: File,
    val name: String,
    val language: ScriptLanguage,
    val size: Long,
    val lastModified: Long,
)

/**
 * Storage and installation rules for scripts imported by the user.
 *
 * User files are kept in `.proxmark3/user-scripts/`; the client searches
 * `.proxmark3/luascripts/` and `.proxmark3/cmdscripts/` instead. Keeping the
 * originals outside those trees means [Pm3Resources.reset] can reinstall the
 * bundled resources without destroying user work.
 */
object Pm3ScriptStore {

    const val USER_DIRECTORY = "user-scripts"

    private const val CLIENT_DIRECTORY = ".proxmark3"
    private const val MAX_STEM_LENGTH = 96
    private val INVALID_STEM_CHARS = Regex("[^A-Za-z0-9._-]")

    /** Extensions accepted by the current build. */
    val supportedExtensions: Set<String> = setOf(
        ScriptLanguage.LUA.extension,
        ScriptLanguage.CMD.extension,
        ScriptLanguage.PYTHON.extension,
    )

    fun languageForName(name: String): ScriptLanguage? {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ScriptLanguage.entries.firstOrNull { it.extension == extension }
    }

    /**
     * Returns a safe, lower-case-extension script name, or null when the file
     * is not a supported script. Stem characters outside the allow-list are
     * replaced with underscores so the name can be passed to `script run`
     * without quoting surprises.
     */
    fun sanitizeName(rawName: String): String? {
        val base = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        val dot = base.lastIndexOf('.')
        if (dot <= 0 || dot == base.lastIndex) return null

        val extension = base.substring(dot + 1).lowercase(Locale.ROOT)
        if (extension !in supportedExtensions) return null

        val stem = base.substring(0, dot)
            .replace(INVALID_STEM_CHARS, "_")
            .trim('.', '-', '_')
            .take(MAX_STEM_LENGTH)
        if (stem.isBlank()) return null

        return "$stem.$extension"
    }

    fun userDirectory(context: Context): File =
        File(Pm3Resources.home(context), "$CLIENT_DIRECTORY/$USER_DIRECTORY")

    fun activeDirectory(context: Context, language: ScriptLanguage): File {
        val subdirectory = when (language) {
            ScriptLanguage.LUA -> "luascripts"
            ScriptLanguage.CMD -> "cmdscripts"
            ScriptLanguage.PYTHON -> "pyscripts"
        }
        return File(Pm3Resources.home(context), "$CLIENT_DIRECTORY/$subdirectory")
    }

    fun list(context: Context): List<Pm3Script> {
        val directory = userDirectory(context)
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile }
            .mapNotNull { file ->
                val language = languageForName(file.name) ?: return@mapNotNull null
                if (language.extension !in supportedExtensions) return@mapNotNull null
                Pm3Script(
                    file = file,
                    name = file.name,
                    language = language,
                    size = file.length(),
                    lastModified = file.lastModified(),
                )
            }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .toList()
    }

    /**
     * Copies a script into the user directory and immediately mirrors it into
     * the client directory, so it can be run even if resources were already
     * installed for this app version.
     */
    fun import(context: Context, displayName: String, input: InputStream): Pm3Script {
        val script = input.use { source ->
            val safeName = sanitizeName(displayName)
                ?: throw IllegalArgumentException("unsupported script name: $displayName")
            val language = languageForName(safeName)
                ?: throw IllegalArgumentException("unsupported script name: $displayName")

            val directory = userDirectory(context).apply { mkdirs() }
            val destination = uniqueTarget(directory, safeName)
            try {
                destination.outputStream().use { sink -> source.copyTo(sink) }
            } catch (t: Throwable) {
                destination.delete()
                throw t
            }
            Pm3Script(
                file = destination,
                name = destination.name,
                language = language,
                size = destination.length(),
                lastModified = destination.lastModified(),
            )
        }
        install(context, script)
        return script
    }

    /** Mirrors every user script into the matching client directory. */
    fun installAll(context: Context) {
        runCatching { list(context) }.getOrElse { return }.forEach { script ->
            runCatching { install(context, script) }
        }
    }

    /** Mirrors one script; overwrites the copy installed previously. */
    fun install(context: Context, script: Pm3Script) {
        val directory = activeDirectory(context, script.language).apply { mkdirs() }
        val destination = File(directory, script.name)
        if (destination.canonicalFile == script.file.canonicalFile) return
        script.file.copyTo(destination, overwrite = true)
    }

    /** Deletes both the user original and the client-visible mirror. */
    fun delete(context: Context, script: Pm3Script): Boolean {
        val mirror = File(activeDirectory(context, script.language), script.name)
        val removedMirror = !mirror.exists() || mirror.delete()
        val removedSource = !script.file.exists() || script.file.delete()
        return removedMirror && removedSource
    }

    private fun uniqueTarget(directory: File, name: String): File {
        var candidate = File(directory, name)
        if (!candidate.exists()) return candidate

        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(directory, "$stem-$counter$extension")
            counter++
        }
        return candidate
    }
}
