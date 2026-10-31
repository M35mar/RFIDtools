// Compose resolves stringResource(id, args...) through Resources.getString,
// which throws MissingFormatArgumentException when the format string declares
// more positional specifiers than the call site passes. Lint does not check
// this, so the sources and values/ are compared here instead.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class StringFormatArgsTest {

    private val moduleDir: File = listOf(".", "core/pm3", "../core/pm3", "../../core/pm3")
        .map { File(it).absoluteFile.normalize() }
        .firstOrNull { File(it, "src/main/res/values/strings.xml").isFile }
        ?: error("module dir not found (cwd=${File("").absolutePath})")

    private fun specsByName(xml: File): Map<String, List<Int>> =
        Regex("""<string name="([^"]+)">(.*?)</string>""")
            .findAll(xml.readText())
            .associate { match ->
                val name = match.groupValues[1]
                val specs = Regex("""%(\d+)\$""")
                    .findAll(match.groupValues[2])
                    .map { it.groupValues[1].toInt() }
                    .sorted()
                    .toList()
                name to specs
            }

    private fun resourceCalls(): List<Triple<String, Int, String>> {
        val calls = mutableListOf<Triple<String, Int, String>>()
        val src = File(moduleDir, "src/main/java")
        src.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            var from = 0
            while (true) {
                val marker = text.indexOf("stringResource(", from)
                if (marker < 0) break
                val open = marker + "stringResource".length
                val close = matchingParen(text, open)
                if (close < 0) break
                val args = splitTopLevel(text.substring(open + 1, close))
                    // Kotlin allows a trailing comma: it leaves an empty tail.
                    .dropLastWhile { it.isBlank() }
                val name = Regex("""^\s*R\.string\.(\w+)\s*$""")
                    .find(args.firstOrNull().orEmpty())
                    ?.groupValues
                    ?.get(1)
                if (name != null) {
                    calls += Triple(name, args.size - 1, "${file.name}:${lineOf(text, marker)}")
                }
                from = close
            }
        }
        return calls
    }

    private fun matchingParen(text: String, open: Int): Int {
        var depth = 0
        var quote: Char? = null
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun splitTopLevel(args: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var quote: Char? = null
        val current = StringBuilder()
        var i = 0
        while (i < args.length) {
            val c = args[i]
            if (quote != null) {
                current.append(c)
                if (c == '\\') {
                    if (i + 1 < args.length) current.append(args[++i])
                } else if (c == quote) {
                    quote = null
                }
            } else {
                when (c) {
                    '"', '\'' -> {
                        quote = c
                        current.append(c)
                    }
                    '(', '[', '{' -> {
                        depth++
                        current.append(c)
                    }
                    ')', ']', '}' -> {
                        depth--
                        current.append(c)
                    }
                    ',' -> {
                        if (depth == 0) {
                            out += current.toString()
                            current.setLength(0)
                        } else {
                            current.append(c)
                        }
                    }
                    else -> current.append(c)
                }
            }
            i++
        }
        out += current.toString()
        return out
    }

    private fun lineOf(text: String, index: Int): Int = text.take(index).count { it == '\n' } + 1

    @Test
    fun `every stringResource call passes as many arguments as the format declares`() {
        val specs = specsByName(File(moduleDir, "src/main/res/values/strings.xml"))
        val problems = resourceCalls().mapNotNull { (name, args, where) ->
            val declared = specs[name] ?: return@mapNotNull "$where: R.string.$name does not exist"
            if (declared.size != args) {
                "$where: R.string.$name declares ${declared.size} placeholder(s), call passes $args"
            } else {
                null
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun `translations keep the same placeholders as the default locale`() {
        val default = specsByName(File(moduleDir, "src/main/res/values/strings.xml"))
        val translated = specsByName(File(moduleDir, "src/main/res/values-it/strings.xml"))
        assertEquals(
            "locale key sets differ",
            default.keys.sorted(),
            translated.keys.sorted(),
        )
        val problems = default.mapNotNull { (name, specs) ->
            val other = translated.getValue(name)
            if (specs != other) "$name: default $specs, it $other" else null
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}
