// Assembly of the .bin file that `hf mfu dump` writes: the 56-byte header
// (GET_VERSION, signature, counters) followed by the raw pages. PACK and
// password are patched into the last pages where the client puts them, so the
// file matches the Proxmark3 output byte for byte.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import java.io.File

/** Length of `mfu_dump_t` up to `data`, from include/mifare.h. */
const val MFU_DUMP_HEADER_BYTES = 56

/** Everything a dump needs, captured from the tag before the file is built. */
class MfuCapture(
    val pages: List<ByteArray>,
    val version: ByteArray,
    val signature: ByteArray,
    /** Three entries: three counter bytes plus the tearing flag. */
    val counters: List<ByteArray>,
    /** PACK returned by PWD_AUTH; null leaves the page as read. */
    val pack: ByteArray?,
    /** Password to store like `hf mfu dump -p`; null keeps the read page. */
    val password: ByteArray?,
) {
    init {
        require(pages.size >= 2 && pages.all { it.size == 4 }) {
            "a dump needs at least two four-byte pages"
        }
        require(counters.size == 3 && counters.all { it.size == 4 }) {
            "expected three four-byte counters"
        }
    }

    val layout: MfuLayout get() = MfuLayout(pages.size)

    /** Seven-byte UID: serial page plus the four bytes of page 1. */
    val uid: ByteArray
        get() = pages[0].copyOfRange(0, 3) + pages[1].copyOfRange(0, 4)

    val uidHex: String get() = uid.toHex()

    /** PM3 default file name without extension, uppercase hex. */
    val defaultName: String get() = "hf-mfu-$uidHex-dump"
}

object MfuDumpWriter {

    private const val PAGES_OFFSET = 11
    private const val SIGNATURE_OFFSET = 12
    private const val COUNTERS_OFFSET = 44

    /**
     * Byte layout of `hf mfu dump`:
     * `version[8] tbo[2] tbo1 pages signature[32] counter_tearing[3][4] data`.
     * PACK always overwrites the first two bytes of the last page (zeroes when
     * authentication was not attempted, as the client does), the password only
     * when one was supplied.
     */
    fun toBin(capture: MfuCapture): ByteArray {
        val body = ByteArray(capture.pages.size * 4)
        capture.pages.forEachIndexed { index, page -> page.copyInto(body, index * 4) }
        (capture.pack ?: ByteArray(2)).copyInto(body, body.size - 4, 0, 2)
        capture.password?.takeIf { it.size == 4 }?.copyInto(body, body.size - 8)

        val header = ByteArray(MFU_DUMP_HEADER_BYTES)
        capture.version.copyInto(header, 0, 0, minOf(capture.version.size, 8))
        // tbo and tbo1 are never filled by the client: they stay zero.
        header[PAGES_OFFSET] = (capture.pages.size - 1).toByte()
        capture.signature.copyInto(
            header,
            SIGNATURE_OFFSET,
            0,
            minOf(capture.signature.size, 32),
        )
        capture.counters.forEachIndexed { index, counter ->
            counter.copyInto(header, COUNTERS_OFFSET + index * 4, 0, 4)
        }
        return header + body
    }
}

/** File-name rules for the dumps created on the phone. */
object MfuDumpFiles {

    private const val EXTENSION = ".bin"
    private const val MAX_LENGTH = 80
    private val FORBIDDEN = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    /**
     * The name as it will be written: trimmed, with exactly one lowercase
     * `.bin`, or null when it cannot be used (separators, control characters,
     * hidden file, too long).
     */
    fun fileName(raw: String): String? {
        val trimmed = raw.trim().trimEnd(' ', '.')
        if (trimmed.isEmpty() || trimmed.startsWith(".")) return null
        if (FORBIDDEN.containsMatchIn(trimmed)) return null
        val base = if (trimmed.length > EXTENSION.length &&
            trimmed.endsWith(EXTENSION, ignoreCase = true)
        ) {
            trimmed.dropLast(EXTENSION.length).trimEnd(' ', '.')
        } else {
            trimmed
        }
        if (base.isEmpty()) return null
        val name = base + EXTENSION
        return if (name.length > MAX_LENGTH) null else name
    }

    /** First free name in [dir], adding `-1`, `-2`, ... on collisions. */
    fun unique(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val base = name.dropLast(EXTENSION.length)
        var index = 1
        while (target.exists()) {
            target = File(dir, "$base-$index$EXTENSION")
            index++
        }
        return target
    }

    /**
     * Key file that goes with [dump], following the client convention:
     * `hf-mf-<UID>-dump.bin` is paired with `hf-mf-<UID>-key.bin`. Keep in
     * step with [keyFileCandidate], which looks the file up on restore.
     */
    fun keyFileFor(dump: File): File {
        val base = dump.name.substringBeforeLast('.', dump.name)
        val stem = if (base.endsWith(DUMP_SUFFIX)) base.removeSuffix(DUMP_SUFFIX) else base
        return File(dump.parentFile, "$stem-key$EXTENSION")
    }

    /**
     * First free dump name whose key file is free too, so the numbered suffix
     * (`-1`, `-2`, ...) stays the same for both files and `keyFileCandidate`
     * still finds the pair.
     */
    fun uniqueDump(dir: File, name: String): File {
        val base = name.dropLast(EXTENSION.length)
        var index = 0
        while (true) {
            val candidate = File(dir, if (index == 0) name else "$base-$index$EXTENSION")
            if (!candidate.exists() && !keyFileFor(candidate).exists()) return candidate
            index++
        }
    }

    /** Suffix the client appends to the dump name, removed for the key file. */
    const val DUMP_SUFFIX = "-dump"
}
