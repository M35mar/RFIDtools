// ISO/IEC 15693 command builders and parsers. The Android NfcV transport adds
// and checks the CRC, so the frames here are payload only. Flags 0x00 means
// unaddressed: only one card is in the field in reader mode.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

object Iso15693 {

    const val CMD_READ_SINGLE_BLOCK = 0x20
    const val CMD_WRITE_SINGLE_BLOCK = 0x21
    const val CMD_LOCK_BLOCK = 0x22
    const val CMD_READ_MULTIPLE_BLOCKS = 0x23
    const val CMD_WRITE_MULTIPLE_BLOCKS = 0x24
    const val CMD_GET_SYSTEM_INFO = 0x2B
    const val CMD_READ_SIGNATURE = 0x2D

    /** ICODE SLIX/SLIX2 password commands. */
    const val CMD_SET_PASSWORD = 0x02
    const val CMD_WRITE_PASSWORD = 0x03

    const val FLAG_UNADDRESSED = 0x00
    const val FLAG_ADDRESSED = 0x02
    const val FLAG_HIGH_DATA_RATE = 0x20

    const val INFO_DSFID = 0x01
    const val INFO_AFI = 0x02
    const val INFO_MEMORY = 0x04
    const val INFO_IC = 0x08

    fun getSystemInfo(uid: ByteArray? = null): ByteArray = frame(CMD_GET_SYSTEM_INFO, uid)

    fun readBlock(block: Int, uid: ByteArray? = null): ByteArray =
        frame(CMD_READ_SINGLE_BLOCK, uid, byteArrayOf(block.toByte()))

    fun readMultipleBlocks(start: Int, count: Int, uid: ByteArray? = null): ByteArray {
        require(count in 1..256) { "block count out of range" }
        return frame(
            CMD_READ_MULTIPLE_BLOCKS,
            uid,
            byteArrayOf(start.toByte(), (count - 1).toByte()),
        )
    }

    fun writeBlock(block: Int, data: ByteArray, uid: ByteArray? = null): ByteArray =
        frame(CMD_WRITE_SINGLE_BLOCK, uid, byteArrayOf(block.toByte()) + data)

    fun lockBlock(block: Int, uid: ByteArray? = null): ByteArray =
        frame(CMD_LOCK_BLOCK, uid, byteArrayOf(block.toByte()))

    fun writeAfi(afi: Int, uid: ByteArray? = null): ByteArray =
        frame(0x27, uid, byteArrayOf(afi.toByte()))

    fun writeDsfid(dsfid: Int, uid: ByteArray? = null): ByteArray =
        frame(0x29, uid, byteArrayOf(dsfid.toByte()))

    fun setPassword(password: ByteArray, uid: ByteArray? = null): ByteArray =
        frame(CMD_SET_PASSWORD, uid, password)

    fun writePassword(password: ByteArray, uid: ByteArray? = null): ByteArray =
        frame(CMD_WRITE_PASSWORD, uid, password)

    fun lockPassword(uid: ByteArray? = null): ByteArray =
        frame(0x2C, uid)

    fun readSignature(uid: ByteArray? = null): ByteArray =
        frame(CMD_READ_SIGNATURE, uid)

    private fun frame(command: Int, uid: ByteArray?, data: ByteArray = ByteArray(0)): ByteArray {
        val out = java.io.ByteArrayOutputStream(2 + (uid?.size ?: 0) + data.size)
        out.write(if (uid == null) FLAG_UNADDRESSED else FLAG_ADDRESSED)
        out.write(command)
        if (uid != null) out.write(uid)
        out.write(data)
        return out.toByteArray()
    }

    /** System information returned by command 0x2B. */
    class SystemInfo(
        val uid: ByteArray,
        val infoFlags: Int,
        val dsfid: Int?,
        val afi: Int?,
        val blockCount: Int?,
        val blockSize: Int?,
        val icReference: Int?,
        val raw: ByteArray,
    ) {
        val memoryBytes: Int?
            get() = if (blockCount != null && blockSize != null) blockCount * blockSize else null
    }

    /**
     * Parses the answer to GET SYSTEM INFO. Layout: flags, info flags, UID
     * (8 bytes), then the optional DSFID, AFI, memory size and IC reference.
     */
    fun parseSystemInfo(response: ByteArray): SystemInfo? {
        if (response.size < 10) return null
        val infoFlags = response[1].toInt() and 0xFF
        val uid = response.copyOfRange(2, 10)
        var cursor = 10
        val dsfid = if (infoFlags and INFO_DSFID != 0) {
            response.getOrNull(cursor++)?.toInt()?.and(0xFF)
        } else {
            null
        }
        val afi = if (infoFlags and INFO_AFI != 0) {
            response.getOrNull(cursor++)?.toInt()?.and(0xFF)
        } else {
            null
        }
        var blocks: Int? = null
        var blockSize: Int? = null
        if (infoFlags and INFO_MEMORY != 0) {
            val count = response.getOrNull(cursor++)
            val sizeCode = response.getOrNull(cursor++)
            if (count != null && sizeCode != null) {
                blocks = (count.toInt() and 0xFF) + 1
                blockSize = (sizeCode.toInt() and 0x1F) + 1
            }
        }
        val icReference = if (infoFlags and INFO_IC != 0) {
            response.getOrNull(cursor)?.toInt()?.and(0xFF)
        } else {
            null
        }
        return SystemInfo(
            uid = uid,
            infoFlags = infoFlags,
            dsfid = dsfid,
            afi = afi,
            blockCount = blocks,
            blockSize = blockSize,
            icReference = icReference,
            raw = response.copyOf(),
        )
    }

    /** Data of a READ SINGLE / MULTIPLE BLOCK answer, flags byte removed. */
    fun parseBlockData(response: ByteArray): ByteArray? {
        if (response.size < 2) return null
        // Flags byte 0x00 is success; any error flag means no data.
        if ((response[0].toInt() and 0x01) != 0) return null
        return response.copyOfRange(1, response.size)
    }
}
