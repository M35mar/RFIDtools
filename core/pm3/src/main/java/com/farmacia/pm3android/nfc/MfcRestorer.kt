// MIFARE Classic restore engine.
//
// The sequence follows MIFARE Classic Tool's "Write dump", which is known to
// work on tags whose UID differs from the dump:
//
//   1. build a key map on the *tag*: every candidate key is tried as Key A and
//      as Key B on every sector (see [MfcKeyMap]);
//   2. pre-check without writing: every sector must open, every block that
//      differs must be writable (directly or after installing the dump trailer)
//      and the trailer write key must be known. If anything is missing the
//      restore stops before the first byte;
//   3. write data blocks first, the sector trailer last. The trailer content
//      (Key A, Key B, access bits) always comes from the dump, never from the
//      key file, and it is written with the key the *current* trailer asks for
//      (Key A or Key B);
//   4. retry each write once: writing several blocks in a row occasionally
//      fails on Android for timing reasons, as MCT documents.
//
// Block 0 is never written, so the target keeps its UID and BCC. Data blocks
// whose current access condition is read-only are repaired by installing the
// dump trailer first and retrying, as before.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import java.io.IOException

class MfcRestorer(private val transport: ClassicTransport) {

    fun restore(
        dump: MfcDump,
        options: MfcRestoreOptions = MfcRestoreOptions(),
        progress: RestoreProgress? = null,
    ): RestoreReport {
        val started = System.currentTimeMillis()
        checkGeometry(dump)

        // The chosen key file, the keys stored next to the dump and the
        // factory key are all candidates; none of them decides what is written.
        val candidates = MfcKeyMap.flatten(
            options.candidates,
            dump.keys?.allKeys().orEmpty(),
            listOf(MFC_DEFAULT_KEY),
        )
        val progressTotal = dump.size.sectors + dump.size.blocks
        val keyMap = MfcKeyMap.build(
            sectorCount = dump.size.sectors,
            keys = candidates,
            authenticate = { sector, key, keyA ->
                tryAuthenticate(sector, Session(key, keyA))
            },
        ) { sector ->
            progress?.onProgress(sector + 1, progressTotal)
        }

        val unopened = (0 until dump.size.sectors).filter { it !in keyMap }
        if (unopened.isNotEmpty()) {
            // A partial restore is worse than no restore: nothing is written.
            throw MfcPrecheckException(unopenedSectors = unopened)
        }

        preflight(dump, keyMap)

        val steps = arrayOfNulls<StepResult>(dump.size.blocks)
        var done = 0

        fun add(step: StepResult) {
            if (steps[step.index] == null) done++
            steps[step.index] = step
            progress?.onProgress(dump.size.sectors + done, progressTotal)
        }

        for (sector in 0 until dump.size.sectors) {
            val keys = keyMap.getValue(sector)
            val first = MfcGeometry.firstBlockOfSector(sector)
            val count = MfcGeometry.blocksInSector(sector)
            val trailerIndex = first + count - 1
            val currentTrailer = readWithKeys(sector, trailerIndex, keys)
            val planned = planTrailer(sector, dump)
            val currentAccess = currentTrailer
                ?.copyOfRange(6, 10)
                ?.takeIf(MfcAccess::isValid)
            val plannedAccess = planned.data
                ?.copyOfRange(6, 10)
                ?.takeIf(MfcAccess::isValid)
            val access = currentAccess ?: plannedAccess ?: MfcAccess.DEFAULT

            val results = writeDataBlocks(
                first = first,
                count = count,
                dump = dump,
                access = access,
                keys = keys,
                only = null,
                add = ::add,
            )
            val repairable = results.filter {
                (it.status == StepStatus.SKIPPED && it.note == StepNote.READ_ONLY) ||
                    (it.status == StepStatus.FAILED && it.note == StepNote.KEY_B_REQUIRED)
            }

            // Installing the dump trailer is the only way to make a block
            // writable when the *current* ACL forbids it. The retry then uses
            // the keys and the ACL of the dump.
            var trailerDone = false
            if (repairable.isNotEmpty() &&
                planned.data != null &&
                !trailerAlreadySame(planned.data, currentTrailer, keys)
            ) {
                val trailerStep = writeTrailer(
                    sector = sector,
                    trailerIndex = trailerIndex,
                    planned = planned,
                    current = currentTrailer,
                    keys = keys,
                    force = true,
                )
                add(trailerStep)
                trailerDone = true

                if (trailerStep.status == StepStatus.WRITTEN ||
                    trailerStep.status == StepStatus.VERIFIED
                ) {
                    val payload = planned.data
                    writeDataBlocks(
                        first = first,
                        count = count,
                        dump = dump,
                        access = payload.copyOfRange(6, 10),
                        keys = SectorKeys(
                            payload.copyOfRange(0, 6),
                            payload.copyOfRange(10, 16),
                        ),
                        only = repairable.map { it.index }.toSet(),
                        add = ::add,
                    )
                }
            }

            if (!trailerDone) {
                add(
                    writeTrailer(
                        sector = sector,
                        trailerIndex = trailerIndex,
                        planned = planned,
                        current = currentTrailer,
                        keys = keys,
                        force = false,
                    ),
                )
            }
        }

        return RestoreReport(
            dumpName = dump.name,
            tagLabel = dump.size.label,
            steps = steps.filterNotNull().sortedBy { it.index },
            durationMs = System.currentTimeMillis() - started,
        )
    }

    // -----------------------------------------------------------------------
    // Pre-check
    // -----------------------------------------------------------------------

    private fun checkGeometry(dump: MfcDump) {
        val sectors = transport.sectorCount
        val sameSectors = sectors == dump.size.sectors
        val sameLayout = sameSectors && (0 until sectors).all { sector ->
            transport.blockCount(sector) == MfcGeometry.blocksInSector(sector)
        }
        if (!sameLayout) {
            throw MfcPrecheckException(dumpSize = dump.size, tagSectors = sectors)
        }
    }

    /**
     * Reads the tags without writing and verifies that every block which
     * differs from the dump can be written. A block that is already identical
     * is not a problem, even when its access condition is read-only.
     */
    private fun preflight(dump: MfcDump, keyMap: Map<Int, SectorKeys>) {
        val missing = mutableListOf<MfcMissingKey>()

        for (sector in 0 until dump.size.sectors) {
            val keys = keyMap[sector] ?: continue
            val first = MfcGeometry.firstBlockOfSector(sector)
            val count = MfcGeometry.blocksInSector(sector)
            val trailerIndex = first + count - 1
            val current = readWithKeys(sector, trailerIndex, keys)
            if (current == null) {
                // A sector that cannot even show its trailer cannot be checked.
                missing += MfcMissingKey(sector, block = null, keyB = false)
                continue
            }
            val currentAccess = current
                .copyOfRange(6, 10)
                .takeIf(MfcAccess::isValid)
            val planned = planTrailer(sector, dump)
            val plannedAccess = planned.data
                ?.copyOfRange(6, 10)
                ?.takeIf(MfcAccess::isValid)
            val plannedKeys = planned.data?.let { payload ->
                SectorKeys(payload.copyOfRange(0, 6), payload.copyOfRange(10, 16))
            }
            val trailerNeedsWrite = planned.data != null &&
                !trailerAlreadySame(planned.data, current, keys)
            val trailerSession = if (trailerNeedsWrite) {
                trailerSession(keys, currentAccess)
            } else {
                null
            }
            if (trailerNeedsWrite && trailerSession == null) {
                val requirement = currentAccess?.let(MfcAccess::trailerWriteRequirement)
                missing += MfcMissingKey(
                    sector = sector,
                    block = null,
                    keyB = requirement == MfcKeyRequirement.B,
                )
            }

            // Repair through the dump trailer is possible only when the trailer
            // itself can be rewritten; rewriting an identical one changes
            // nothing, so it does not count.
            val canRepair = trailerNeedsWrite && trailerSession != null &&
                plannedKeys != null && plannedAccess != null

            for (offset in 0 until count - 1) {
                val block = first + offset
                if (block == 0) continue
                val payload = dump.block(block)
                val existing = readWithKeys(sector, block, keys)
                if (existing != null && existing.contentEquals(payload)) continue

                val requirement = currentAccess?.let {
                    MfcAccess.writeKeyRequirement(it, offset)
                } ?: MfcKeyRequirement.ANY
                if (requirement != MfcKeyRequirement.NONE &&
                    keys.sessionFor(requirement) != null
                ) {
                    continue
                }

                val repaired = canRepair &&
                    MfcAccess.writeKeyRequirement(plannedAccess, offset).let { retry ->
                        retry != MfcKeyRequirement.NONE &&
                            plannedKeys.sessionFor(retry) != null
                    }
                if (!repaired) {
                    val retry = plannedAccess?.let {
                        MfcAccess.writeKeyRequirement(it, offset)
                    }
                    missing += MfcMissingKey(
                        sector = sector,
                        block = block,
                        keyB = requirement == MfcKeyRequirement.B ||
                            retry == MfcKeyRequirement.B,
                    )
                }
            }
        }

        if (missing.isNotEmpty()) {
            throw MfcPrecheckException(missingKeys = missing)
        }
    }

    // -----------------------------------------------------------------------
    // Write pass
    // -----------------------------------------------------------------------

    private fun writeDataBlocks(
        first: Int,
        count: Int,
        dump: MfcDump,
        access: ByteArray,
        keys: SectorKeys,
        only: Set<Int>?,
        add: (StepResult) -> Unit,
    ): List<StepResult> {
        val sector = MfcGeometry.sectorOfBlock(first)
        val results = mutableListOf<StepResult>()

        fun emit(step: StepResult) {
            results += step
            add(step)
        }

        for (offset in 0 until count - 1) {
            val block = first + offset
            if (only != null && block !in only) continue
            if (block == 0) {
                emit(StepResult(block, StepStatus.SKIPPED, StepNote.UID_KEPT))
                continue
            }

            val payload = dump.block(block)
            val existing = readWithKeys(sector, block, keys)
            if (existing != null && existing.contentEquals(payload)) {
                emit(StepResult(block, StepStatus.UNCHANGED))
                continue
            }

            val requirement = if (MfcAccess.isValid(access)) {
                MfcAccess.writeKeyRequirement(access, offset)
            } else {
                MfcKeyRequirement.ANY
            }
            if (requirement == MfcKeyRequirement.NONE) {
                emit(StepResult(block, StepStatus.SKIPPED, StepNote.READ_ONLY))
                continue
            }

            val session = keys.sessionFor(requirement)
            if (session == null) {
                emit(
                    StepResult(
                        block,
                        StepStatus.FAILED,
                        if (requirement == MfcKeyRequirement.B) {
                            StepNote.KEY_B_REQUIRED
                        } else {
                            StepNote.AUTH_FAILED
                        },
                    ),
                )
                continue
            }
            if (!tryAuthenticate(sector, session)) {
                emit(StepResult(block, StepStatus.FAILED, StepNote.AUTH_FAILED))
                continue
            }

            if (!writeWithRetry(block, payload)) {
                emit(StepResult(block, StepStatus.FAILED, StepNote.WRITE_FAILED))
                continue
            }

            val readBack = tryRead(block)
            emit(
                StepResult(
                    block,
                    if (readBack != null && readBack.contentEquals(payload)) {
                        StepStatus.VERIFIED
                    } else {
                        StepStatus.WRITTEN
                    },
                ),
            )
        }

        return results
    }

    private fun writeTrailer(
        sector: Int,
        trailerIndex: Int,
        planned: PlannedTrailer,
        current: ByteArray?,
        keys: SectorKeys,
        /** Repair path: rewrite even when the trailer looks identical. */
        force: Boolean,
    ): StepResult {
        val payload = planned.data
            ?: return StepResult(trailerIndex, StepStatus.SKIPPED, planned.note)

        if (!force && trailerAlreadySame(payload, current, keys)) {
            return StepResult(trailerIndex, StepStatus.UNCHANGED, planned.note)
        }

        // The trailer write key comes from the tag's *current* access
        // conditions: a trailer with 011/100 only accepts Key B.
        val currentAccess = current
            ?.copyOfRange(6, 10)
            ?.takeIf(MfcAccess::isValid)
        val session = trailerSession(keys, currentAccess)
            ?: return StepResult(trailerIndex, StepStatus.FAILED, StepNote.READ_ONLY)
        if (!tryAuthenticate(sector, session)) {
            return StepResult(trailerIndex, StepStatus.FAILED, StepNote.AUTH_FAILED)
        }

        if (!writeWithRetry(trailerIndex, payload)) {
            return StepResult(trailerIndex, StepStatus.FAILED, StepNote.WRITE_FAILED)
        }

        // Re-authenticating with a key we just wrote is the only way to confirm
        // the trailer took effect.
        val confirmed = reauthenticate(sector, payload) != null
        return StepResult(
            trailerIndex,
            if (confirmed) StepStatus.VERIFIED else StepStatus.WRITTEN,
            planned.note,
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun sessionsOf(keys: SectorKeys): List<Session> = buildList {
        keys.sessionForAny(preferA = true)?.let(::add)
        keys.sessionForAny(preferA = false)?.let(::add)
    }.distinctBy { it.keyA to it.key.toHex() }

    /** Reads a block with Key A and, when that does not answer, with Key B. */
    private fun readWithKeys(sector: Int, block: Int, keys: SectorKeys): ByteArray? {
        for (session in sessionsOf(keys)) {
            if (!tryAuthenticate(sector, session)) continue
            tryRead(block)?.let { return it }
        }
        return null
    }

    private fun trailerSession(keys: SectorKeys, access: ByteArray?): Session? {
        val requirement = access?.let(MfcAccess::trailerWriteRequirement)
            ?: MfcKeyRequirement.ANY
        return keys.sessionFor(requirement)
    }

    /**
     * The trailer read back from the tag masks Key A. Only the key map proves
     * whether Key A already is the planned one; Key B is readable when the
     * access conditions allow it, and a masked (all zero) Key B must not be
     * mistaken for a real one.
     */
    private fun trailerAlreadySame(
        planned: ByteArray,
        current: ByteArray?,
        keys: SectorKeys,
    ): Boolean {
        val accessSame = current != null &&
            current.copyOfRange(6, 10).contentEquals(planned.copyOfRange(6, 10))
        val keyASame = keys.keyA?.contentEquals(planned.copyOfRange(0, 6)) == true
        val keyBSame = current != null &&
            current.copyOfRange(10, 16).contentEquals(planned.copyOfRange(10, 16))
        return accessSame && keyASame && keyBSame
    }

    private fun reauthenticate(sector: Int, trailer: ByteArray): Session? {
        val keyABytes = trailer.copyOfRange(0, 6)
        if (keyABytes.any { it != 0.toByte() }) {
            val keyA = Session(keyABytes, true)
            if (tryAuthenticate(sector, keyA)) return keyA
        }
        val keyBBytes = trailer.copyOfRange(10, 16)
        if (keyBBytes.any { it != 0.toByte() }) {
            val keyB = Session(keyBBytes, false)
            if (tryAuthenticate(sector, keyB)) return keyB
        }
        return null
    }

    private fun tryAuthenticate(sector: Int, session: Session): Boolean = try {
        transport.authenticate(sector, session.key, session.keyA)
    } catch (_: IOException) {
        false
    }

    private fun tryRead(block: Int): ByteArray? = try {
        transport.readBlock(block)
    } catch (_: IOException) {
        null
    }

    private fun tryWrite(block: Int, payload: ByteArray): Boolean = try {
        transport.writeBlock(block, payload)
        true
    } catch (_: IOException) {
        false
    }

    /**
     * MCT retries every write once: consecutive writes sometimes fail on
     * Android for timing reasons even when the block is writable.
     */
    private fun writeWithRetry(block: Int, payload: ByteArray): Boolean {
        repeat(WRITE_ATTEMPTS) {
            if (tryWrite(block, payload)) return true
        }
        return false
    }

    private class PlannedTrailer(val data: ByteArray?, val note: StepNote?)

    /**
     * Trailer content from the dump: the keys stored next to the dump win over
     * the masked bytes of the trailer, and an invalid ACL is replaced with the
     * factory one. A missing key would make the sector unreachable, so the
     * trailer is left untouched instead.
     */
    private fun planTrailer(sector: Int, dump: MfcDump): PlannedTrailer {
        val data = dump.trailer(sector).copyOf()
        var note: StepNote? = null

        val keyA = dump.keys
            ?.keyAFor(sector)
            ?.takeIf { key -> key.any { it != 0.toByte() } }
            ?: data.copyOfRange(0, 6)
        val keyB = dump.keys
            ?.keyBFor(sector)
            ?.takeIf { key -> key.any { it != 0.toByte() } }
            ?: data.copyOfRange(10, 16)

        if (keyA.all { it == 0.toByte() } || keyB.all { it == 0.toByte() }) {
            return PlannedTrailer(null, StepNote.KEEP_CURRENT_KEYS)
        }

        keyA.copyInto(data, 0)
        keyB.copyInto(data, 10)

        if (!MfcAccess.isValid(data.copyOfRange(6, 10))) {
            MfcAccess.DEFAULT.copyInto(data, 6)
            note = StepNote.ACCESS_FIXED
        }
        return PlannedTrailer(data, note)
    }

    private companion object {
        /** One retry is enough; MCT does exactly the same. */
        const val WRITE_ATTEMPTS = 2
    }
}
