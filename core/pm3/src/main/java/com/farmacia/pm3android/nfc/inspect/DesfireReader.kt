// Authenticated read of the DESFire files whose metadata the keyless inspector
// already collected. One AES key is tried on every application and every file
// it can open; a refused file stays empty instead of hiding the rest.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import java.io.ByteArrayOutputStream

object DesfireReader {

    /** Small enough to stay in a single ISO-DEP frame. */
    private const val CHUNK = 128

    /** Upper bound for a corrupt or hostile file size. */
    private const val MAX_READ = 64 * 1024

    private const val CMD_READ_DATA = 0xBD
    private const val CMD_READ_RECORDS = 0xBB
    private const val CMD_GET_VALUE = 0x6C

    /**
     * @param abort rethrows tag loss: Android's TagLostException is not visible
     *   from this package, so the caller passes a predicate.
     */
    fun read(
        client: DesfireClient,
        details: DesfireDetails,
        keyNo: Int,
        key: ByteArray,
        abort: (Throwable) -> Boolean = { false },
    ): DesfireDetails {
        val applications = details.applications ?: return details

        val updated = applications.map { application ->
            if (application.files.isNullOrEmpty()) {
                application
            } else {
                readApplication(client, application, keyNo, key, abort)
            }
        }

        return DesfireDetails(
            version = details.version,
            picc = details.picc,
            applications = updated,
            cardUid = details.cardUid,
        )
    }

    private fun readApplication(
        client: DesfireClient,
        application: DesfireApplication,
        keyNo: Int,
        key: ByteArray,
        abort: (Throwable) -> Boolean,
    ): DesfireApplication {
        try {
            client.selectApplication(application.id)
        } catch (error: Throwable) {
            if (abort(error)) throw error
            return application.withFiles(application.files, newLocked = true)
        }

        val session = try {
            DesfireSession.authenticate(client, keyNo, key)
        } catch (error: Throwable) {
            if (abort(error)) throw error
            return application.withFiles(application.files, newLocked = true)
        }

        var refused = false
        val files = application.files?.map { file ->
            val mode = commMode(file.communication) ?: return@map file
            try {
                file.withData(readFile(client, session, file, mode))
            } catch (error: Throwable) {
                if (abort(error)) throw error
                refused = true
                file
            }
        }

        return application.withFiles(files, newLocked = refused)
    }

    private fun readFile(
        client: DesfireClient,
        session: DesfireSession,
        file: DesfireFile,
        mode: DesfireCommMode,
    ): ByteArray = when (file.type) {
        DesfireFileType.VALUE -> session.command(
            client,
            CMD_GET_VALUE,
            byteArrayOf(file.id.toByte()),
            mode,
        )

        DesfireFileType.LINEAR_RECORD, DesfireFileType.CYCLIC_RECORD -> readRecords(
            client,
            session,
            file,
            mode,
        )

        DesfireFileType.STANDARD_DATA, DesfireFileType.BACKUP_DATA -> readBytes(
            client,
            session,
            file.id,
            file.sizeBytes ?: 0,
            mode,
        )

        DesfireFileType.UNKNOWN -> ByteArray(0)
    }

    /** Standard and backup files: offset and length are byte counts. */
    private fun readBytes(
        client: DesfireClient,
        session: DesfireSession,
        fileId: Int,
        total: Int,
        mode: DesfireCommMode,
    ): ByteArray {
        if (total <= 0) return ByteArray(0)
        val limit = minOf(total, MAX_READ)
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset < limit) {
            val length = minOf(CHUNK, limit - offset)
            val request = byteArrayOf(fileId.toByte()) +
                le24(offset) +
                le24(length)
            val chunk = session.command(client, CMD_READ_DATA, request, mode)
            out.write(chunk)
            if (chunk.size < length) break
            offset += length
        }
        return out.toByteArray()
    }

    /** Record files: offset and length are record counts, not bytes. */
    private fun readRecords(
        client: DesfireClient,
        session: DesfireSession,
        file: DesfireFile,
        mode: DesfireCommMode,
    ): ByteArray {
        val size = file.recordSize ?: return ByteArray(0)
        val count = file.recordCount ?: return ByteArray(0)
        if (size <= 0 || count <= 0) return ByteArray(0)

        val perChunk = maxOf(1, CHUNK / size)
        val out = ByteArrayOutputStream()
        var index = 0
        while (index < count) {
            val take = minOf(perChunk, count - index)
            val request = byteArrayOf(file.id.toByte()) + le24(index) + le24(take)
            val chunk = session.command(client, CMD_READ_RECORDS, request, mode)
            out.write(chunk)
            if (chunk.size < take * size) break
            index += take
        }
        return out.toByteArray()
    }

    private fun commMode(communication: Int?): DesfireCommMode? = when (communication) {
        0 -> DesfireCommMode.PLAIN
        1 -> DesfireCommMode.MACED
        // 3 is enciphered: the secure channel is not implemented yet.
        else -> null
    }

    private fun le24(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
    )
}
