// MIFARE Ultralight EV1 / NTAG21x restore engine.
//
// The whole ISO 14443-3A command set needed is small: READ 0x30, WRITE 0xA2,
// PWD_AUTH 0x1B and GET_VERSION 0x60. The engine is pure Kotlin and talks to
// the tag through [UltralightTransport], so the sequence can be tested without
// hardware.
//
// Page map (verified against the NXP MF0ULX1 datasheet):
//   MF0UL11 (20 pagine): utente 4..15, CFG0 16, CFG1 17, PWD 18, PACK 19
//   MF0UL21 (41 pagine): utente 4..35, lock 36, CFG0 37, CFG1 38, PWD 39, PACK 40
//   NTAG21x             : utente 4..(pagine-6), lock pagine-5, poi CFG0..PACK
//
// Pages 0..3 (UID, BCC, lock statici, OTP) are never written. Configuration is
// written last: user data, PACK, PWD, CFG0 (AUTH0), CFG1 (ACCESS). The CFGLCK
// bit is never set: it is irreversible and would prevent future restores.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import java.io.IOException

class MfuRestorer(private val transport: UltralightTransport) {

    private companion object {
        const val CMD_READ = 0x30
        const val CMD_WRITE = 0xA2
        const val CMD_PWD_AUTH = 0x1B
        const val CMD_GET_VERSION = 0x60
        const val ACK = 0x0A
        const val ACCESS_CFGLCK = 0x40
    }

    private data class AuthResult(val password: ByteArray?, val pack: ByteArray?)

    /** GET_VERSION, or null on tags (clones, Ultralight C) that do not answer. */
    fun readVersion(): ByteArray? = try {
        transport.transceive(byteArrayOf(CMD_GET_VERSION.toByte()))
            .takeIf { it.size >= 8 }
    } catch (_: IOException) {
        null
    }

    /** Current tag content, used for the differential restore. */
    fun readSnapshot(
        dump: MfuDump,
        options: MfuRestoreOptions = MfuRestoreOptions(),
        progress: RestoreProgress? = null,
    ): MfuSnapshot {
        validate(dump)
        val version = readVersion()
        verifyVariant(version, dump)
        val auth = authenticateCascade(dump, options)
        return readSnapshotInternal(dump, auth, version, progress)
    }

    fun restore(
        dump: MfuDump,
        options: MfuRestoreOptions = MfuRestoreOptions(),
        snapshot: MfuSnapshot? = null,
        progress: RestoreProgress? = null,
    ): RestoreReport {
        validate(dump)
        val started = System.currentTimeMillis()
        val layout = dump.layout
        val total = dump.pages.size
        val steps = mutableListOf<StepResult>()

        fun add(step: StepResult) {
            steps += step
            progress?.onProgress(steps.size, total)
        }

        val version = readVersion()
        verifyVariant(version, dump)
        val auth = authenticateCascade(dump, options)
        val current = snapshot ?: readSnapshotInternal(dump, auth, version, null)

        // Never written: UID/BCC, static lock bits and the OTP page.
        add(StepResult(0, StepStatus.SKIPPED, StepNote.UID_KEPT))
        add(StepResult(1, StepStatus.SKIPPED, StepNote.UID_KEPT))
        add(StepResult(2, StepStatus.SKIPPED, StepNote.LOCK_BITS))
        add(StepResult(3, StepStatus.SKIPPED, StepNote.OTP))

        for (page in layout.userFirst..layout.userLast) {
            add(writePageIfNeeded(page, dump.page(page), current.pages[page]))
        }
        if (layout.lockPage >= 0) {
            add(StepResult(layout.lockPage, StepStatus.SKIPPED, StepNote.LOCK_BITS))
        }

        val dumpPwd = dump.page(layout.pwdPage)
        val dumpPack = dump.page(layout.packPage)
        val dumpCfg0 = dump.page(layout.cfg0)
        val dumpCfg1 = dump.page(layout.cfg1)
        val desiredCfg1 = dumpCfg1.copyOf()
        val locksConfig = (desiredCfg1[0].toInt() and ACCESS_CFGLCK) != 0
        desiredCfg1[0] = (desiredCfg1[0].toInt() and ACCESS_CFGLCK.inv()).toByte()

        val passwordKnown = dumpPwd.any { it != 0.toByte() }
        val configKnown = dumpCfg0.any { it != 0.toByte() } ||
            dumpCfg1.any { it != 0.toByte() }
        val targetLocked = (current.pages[layout.cfg1][0].toInt() and ACCESS_CFGLCK) != 0

        var finalPassword = auth.password

        when {
            !options.writeConfig -> {
                // User pages only: data restored, protection left as it is.
                add(StepResult(layout.packPage, StepStatus.SKIPPED))
                add(StepResult(layout.pwdPage, StepStatus.SKIPPED))
                add(StepResult(layout.cfg0, StepStatus.SKIPPED))
                add(StepResult(layout.cfg1, StepStatus.SKIPPED))
            }

            targetLocked -> {
                // CFGLCK makes CFG0/CFG1/PWD/PACK read-only for good.
                add(StepResult(layout.packPage, StepStatus.SKIPPED, StepNote.CONFIG_LOCKED))
                add(StepResult(layout.pwdPage, StepStatus.SKIPPED, StepNote.CONFIG_LOCKED))
                add(StepResult(layout.cfg0, StepStatus.SKIPPED, StepNote.CONFIG_LOCKED))
                add(StepResult(layout.cfg1, StepStatus.SKIPPED, StepNote.CONFIG_LOCKED))
            }

            !configKnown -> {
                add(StepResult(layout.packPage, StepStatus.SKIPPED, StepNote.CONFIG_UNKNOWN))
                add(StepResult(layout.pwdPage, StepStatus.SKIPPED, StepNote.CONFIG_UNKNOWN))
                add(StepResult(layout.cfg0, StepStatus.SKIPPED, StepNote.CONFIG_UNKNOWN))
                add(StepResult(layout.cfg1, StepStatus.SKIPPED, StepNote.CONFIG_UNKNOWN))
            }

            else -> {
                if (!passwordKnown) {
                    // PWD and PACK cannot be read back; all zeroes means the dump
                    // never captured them, so the tag keeps its own.
                    add(StepResult(layout.packPage, StepStatus.SKIPPED, StepNote.KEEP_CURRENT_KEYS))
                    add(StepResult(layout.pwdPage, StepStatus.SKIPPED, StepNote.KEEP_CURRENT_KEYS))
                } else {
                    if (auth.pack != null && auth.pack.contentEquals(dumpPack.copyOf(2))) {
                        add(StepResult(layout.packPage, StepStatus.UNCHANGED))
                    } else {
                        add(writePage(layout.packPage, dumpPack))
                    }
                    if (auth.password != null && auth.password.contentEquals(dumpPwd)) {
                        add(StepResult(layout.pwdPage, StepStatus.UNCHANGED))
                    } else {
                        val step = writePage(layout.pwdPage, dumpPwd)
                        add(step)
                        if (step.status == StepStatus.WRITTEN) finalPassword = dumpPwd
                    }
                }

                add(writePageIfNeeded(layout.cfg0, dumpCfg0, current.pages[layout.cfg0]))
                val cfg1Step = writePageIfNeeded(
                    layout.cfg1,
                    desiredCfg1,
                    current.pages[layout.cfg1],
                )
                add(
                    if (locksConfig) {
                        cfg1Step.copy(note = StepNote.CONFIG_LOCKED)
                    } else {
                        cfg1Step
                    },
                )
            }
        }

        // Read the tag back once: a WRITTEN step becomes VERIFIED only if the
        // tag returns exactly what the dump asked for.
        val verifiable = (layout.userFirst..layout.userLast).toSet() +
            setOf(layout.cfg0, layout.cfg1)
        if (steps.any { it.status == StepStatus.WRITTEN && it.index in verifiable }) {
            finalPassword?.let { authenticate(it) }
            val readBack = runCatching { readAllPages(total, null) }.getOrNull()
            if (readBack != null) {
                for (index in steps.indices) {
                    val step = steps[index]
                    if (step.status != StepStatus.WRITTEN || step.index !in verifiable) continue
                    val desired = if (step.index == layout.cfg1) {
                        desiredCfg1
                    } else {
                        dump.page(step.index)
                    }
                    if (readBack[step.index].contentEquals(desired)) {
                        steps[index] = step.copy(status = StepStatus.VERIFIED)
                    }
                }
            }
        }

        return RestoreReport(
            dumpName = dump.name,
            tagLabel = layout.variant,
            steps = steps.sortedBy { it.index },
            durationMs = System.currentTimeMillis() - started,
        )
    }

    // -----------------------------------------------------------------------
    // Card access
    // -----------------------------------------------------------------------

    private fun validate(dump: MfuDump) {
        if (!dump.layout.supported) {
            throw TagUnusableException(
                "variante non supportata: ${dump.layout.variant}",
            )
        }
    }

    /**
     * GET_VERSION reports the memory size in byte 6. `0x0B` is 48 bytes
     * (MF0UL11), `0x0E` is 128 bytes (MF0UL21); 0x0F/0x11/0x13 are the
     * NTAG213/215/216. Unknown values are trusted as-is: some clones lie.
     */
    private fun verifyVariant(version: ByteArray?, dump: MfuDump) {
        if (version == null || version.size < 7) return
        val expected = when (version[6].toInt() and 0xFF) {
            0x0B -> 20
            0x0E -> 41
            0x0F -> 45
            0x11 -> 135
            0x13 -> 231
            else -> return
        }
        if (expected != dump.pages.size) {
            throw TagUnusableException(
                "il tag è ${MfuLayout(expected).variant}, " +
                    "il dump è ${dump.layout.variant}",
            )
        }
    }

    /**
     * Password cascade: the field typed by the user, the password stored in the
     * dump (PM3 writes it there when `-k` was used) and finally the factory
     * default. Nothing is asked unless every candidate is refused.
     */
    private fun authenticateCascade(dump: MfuDump, options: MfuRestoreOptions): AuthResult {
        val candidates = buildList {
            options.password?.takeIf { it.size == 4 }?.let { add(it) }
            val fromDump = dump.page(dump.layout.pwdPage)
            if (fromDump.any { it != 0.toByte() }) add(fromDump)
            add(MFU_DEFAULT_PASSWORD)
        }
        for (candidate in candidates.distinctBy { it.toHex() }) {
            authenticate(candidate)?.let { return AuthResult(candidate, it) }
        }
        return AuthResult(null, null)
    }

    private fun authenticate(password: ByteArray): ByteArray? {
        val command = byteArrayOf(CMD_PWD_AUTH.toByte()) + password
        val response = try {
            transport.transceive(command)
        } catch (_: IOException) {
            return null
        }
        return if (response.size >= 2) response.copyOfRange(0, 2) else null
    }

    private fun readSnapshotInternal(
        dump: MfuDump,
        auth: AuthResult,
        version: ByteArray?,
        progress: RestoreProgress?,
    ): MfuSnapshot {
        val pages = try {
            readAllPages(dump.pages.size, progress)
        } catch (e: IOException) {
            if (auth.password == null) {
                throw TagAuthException("il tag richiede una password")
            }
            throw e
        }
        return MfuSnapshot(pages, version)
    }

    // -----------------------------------------------------------------------
    // Page I/O
    // -----------------------------------------------------------------------

    private fun readAllPages(total: Int, progress: RestoreProgress?): List<ByteArray> {
        val pages = MutableList(total) { ByteArray(4) }
        var done = 0
        for (start in mfuReadStarts(total)) {
            val chunk = readChunk(start)
            for (offset in 0 until 4) {
                val page = start + offset
                if (page < total) {
                    chunk.copyInto(pages[page], 0, offset * 4, offset * 4 + 4)
                }
            }
            done = minOf(done + 4, total)
            progress?.onProgress(done, total)
        }
        return pages
    }

    private fun readChunk(start: Int): ByteArray {
        val response = transport.transceive(
            byteArrayOf(CMD_READ.toByte(), start.toByte()),
        )
        if (response.size != 16) {
            throw IOException("READ $start: risposta di ${response.size} byte")
        }
        return response
    }

    private fun writePageIfNeeded(
        page: Int,
        desired: ByteArray,
        current: ByteArray?,
    ): StepResult {
        if (current != null && current.contentEquals(desired)) {
            return StepResult(page, StepStatus.UNCHANGED)
        }
        return writePage(page, desired)
    }

    private fun writePage(page: Int, data: ByteArray): StepResult {
        val command = byteArrayOf(CMD_WRITE.toByte(), page.toByte()) + data.copyOf(4)
        return try {
            val response = transport.transceive(command)
            if (response.isNotEmpty() && (response[0].toInt() and 0xFF) == ACK) {
                StepResult(page, StepStatus.WRITTEN)
            } else {
                StepResult(page, StepStatus.FAILED, StepNote.WRITE_FAILED)
            }
        } catch (_: IOException) {
            StepResult(page, StepStatus.FAILED, StepNote.WRITE_FAILED)
        }
    }
}
