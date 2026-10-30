// Result types shared by the two restore engines and rendered by the UI.
// The engines are pure Kotlin: they talk to a tag through a small transport
// interface so the whole write sequence can be unit-tested with a fake card.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import java.io.IOException

/** Outcome of a single block or page. */
enum class StepStatus {
    /** Written this run and not re-read. */
    WRITTEN,

    /** Written and confirmed by reading it back. */
    VERIFIED,

    /** Already identical to the dump, left alone. */
    UNCHANGED,

    /** Deliberately not written (UID, lock bits, configuration lock, ...). */
    SKIPPED,

    /** The tag refused the write or authentication failed. */
    FAILED,
}

/** Why a step was skipped or failed. Rendered as a localised string. */
enum class StepNote {
    UID_KEPT,
    LOCK_BITS,
    OTP,
    CONFIG_LOCKED,
    CONFIG_UNKNOWN,
    ACCESS_FIXED,
    KEEP_CURRENT_KEYS,
    READ_ONLY,
    KEY_B_REQUIRED,
    AUTH_FAILED,
    WRITE_FAILED,
    VERIFY_FAILED,
}

data class StepResult(
    val index: Int,
    val status: StepStatus,
    val note: StepNote? = null,
)

data class RestoreReport(
    val dumpName: String,
    val tagLabel: String,
    val steps: List<StepResult>,
    val durationMs: Long = 0,
) {
    val written: Int get() = steps.count { it.status == StepStatus.WRITTEN || it.status == StepStatus.VERIFIED }
    val unchanged: Int get() = steps.count { it.status == StepStatus.UNCHANGED }
    val skipped: Int get() = steps.count { it.status == StepStatus.SKIPPED }
    val failed: Int get() = steps.count { it.status == StepStatus.FAILED }
    val ok: Boolean get() = failed == 0

    /** Failed steps plus notes worth showing, in card order. */
    val highlights: List<StepResult>
        get() = steps.filter { it.status == StepStatus.FAILED || it.note != null }.distinct()
}

fun interface RestoreProgress {
    fun onProgress(done: Int, total: Int)
}

/** Raised when the tag cannot be used at all (wrong family, moved away, ...). */
class TagUnusableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Raised when the tag keeps refusing authentication and a password is needed. */
class TagAuthException(message: String) : IOException(message)

/**
 * A restore that would start writing without being able to finish. The
 * pre-check aborts before the first write, so the UI can explain exactly which
 * sectors or keys are missing.
 */
class MfcPrecheckException(
    /** Sectors no candidate key opened. */
    val unopenedSectors: List<Int> = emptyList(),
    /** Blocks (or trailers, block = null) whose write key is unknown. */
    val missingKeys: List<MfcMissingKey> = emptyList(),
    /** Set when the dump geometry does not match the tag. */
    val dumpSize: MfcSize? = null,
    val tagSectors: Int = 0,
) : IOException("pre-check MIFARE Classic non superato")

/** A block the pre-check cannot write because [keyB] (or Key A) is unknown. */
data class MfcMissingKey(val sector: Int, val block: Int?, val keyB: Boolean)

/**
 * A phone-NFC read that did not capture the whole card. The dump is not saved:
 * a partial file looks valid and would restore zeroes later.
 */
class MfcIncompleteReadException(
    val missingSectors: List<Int>,
    val unreadableBlocks: Int,
) : IOException("lettura MIFARE Classic incompleta")

// ---------------------------------------------------------------------------
// MIFARE Classic
// ---------------------------------------------------------------------------

/** Six default keys: the transport key of a factory-fresh MIFARE Classic. */
val MFC_DEFAULT_KEY: ByteArray = ByteArray(6) { 0xFF.toByte() }

interface ClassicTransport {
    val sectorCount: Int

    fun blockCount(sector: Int): Int

    fun authenticate(sector: Int, key: ByteArray, keyA: Boolean): Boolean

    fun readBlock(block: Int): ByteArray

    fun writeBlock(block: Int, data: ByteArray)

    /** Stages `value` added to the block in the card's transfer buffer. */
    fun increment(block: Int, value: Int) {
        throw IOException("INCREMENT non supportato da questo trasporto")
    }

    /** Stages `value` removed from the block in the card's transfer buffer. */
    fun decrement(block: Int, value: Int) {
        throw IOException("DECREMENT non supportato da questo trasporto")
    }

    /** Commits the staged value into the block. */
    fun transfer(block: Int) {
        throw IOException("TRANSFER non supportato da questo trasporto")
    }

    fun close()
}

data class MfcRestoreOptions(
    /**
     * Six-byte keys that may open the destination tag: the chosen key file,
     * plus the keys of the dump. Each is tried as Key A and Key B on every
     * sector. They are never written into a trailer.
     */
    val candidates: List<ByteArray> = emptyList(),
)

/** Current content of the card, used for the differential restore. */
class MfcSnapshot(
    val size: MfcSize,
    val blocks: ByteArray,
    val readable: BooleanArray,
) {
    val readableBlocks: Int get() = readable.count { it }

    fun toBin(): ByteArray = blocks.copyOf()

    companion object {
        fun empty(size: MfcSize): MfcSnapshot =
            MfcSnapshot(size, ByteArray(size.bytes), BooleanArray(size.blocks))
    }
}

// ---------------------------------------------------------------------------
// MIFARE Ultralight / NTAG
// ---------------------------------------------------------------------------

/** Four-byte password of a factory-fresh card. */
val MFU_DEFAULT_PASSWORD: ByteArray = ByteArray(4) { 0xFF.toByte() }

/**
 * Raw ISO 14443-A framing. The Ultralight protocol (READ/WRITE/PWD_AUTH) is
 * built on top of it by [MfuRestorer] so it stays testable.
 */
interface UltralightTransport {
    fun transceive(command: ByteArray): ByteArray

    fun close()
}

data class MfuRestoreOptions(
    /** Password typed by the user; when null the dump's own password is used. */
    val password: ByteArray? = null,
    /** Restore AUTH0/ACCESS/PWD/PACK in addition to the user pages. */
    val writeConfig: Boolean = true,
)

/** Current page content of the card, used for the differential restore. */
class MfuSnapshot(
    val pages: List<ByteArray>,
    val version: ByteArray? = null,
) {
    fun toBin(): ByteArray = pages.toMfuBin(version, null)
}
