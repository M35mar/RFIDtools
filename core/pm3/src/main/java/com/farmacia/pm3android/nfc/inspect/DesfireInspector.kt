// DESFire discovery without keys: the free commands of the PICC level and of
// every application, each attempted on its own so a locked card still yields
// what it is willing to answer. Pure Kotlin: the transport is an interface.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

object DesfireInspector {

    /** Field time is finite: a card with more applications is rare. */
    const val MAX_APPLICATIONS = 16

    const val MAX_FILES = 16

    /**
     * Reads version, PICC parameters, applications and their files.
     *
     * @param abort lets the caller stop on a dead tag: Android's
     *   TagLostException is not visible from this package, so the probe passes
     *   a predicate instead of the inspector importing Android.
     */
    fun read(
        client: DesfireClient,
        abort: (Throwable) -> Boolean = { false },
    ): DesfireDetails? {
        val version = attempt(abort) { client.getVersion() } ?: return null

        val keySettings = attempt(abort) { client.getKeySettings() }
        val picc = DesfirePicc(
            freeMemory = attempt(abort) { client.getFreeMemory() },
            keySettings = keySettings?.settings,
            keyCount = keySettings?.keyCount,
            keyVersion = attempt(abort) { client.getKeyVersion(0) },
        )

        val applicationIds = attempt(abort) { client.getApplicationIds() }
        val dfNames = attempt(abort) { client.getDfNames() }
            .orEmpty()
            .associateBy { it.applicationId }

        val applications = applicationIds?.take(MAX_APPLICATIONS)?.map { id ->
            readApplication(client, id, dfNames[id]?.name, abort)
        }

        // Leave the card on the PICC level: the reading code that follows
        // expects the default selection.
        attempt(abort) { client.selectApplication(0) }

        return DesfireDetails(
            version = version,
            picc = picc,
            applications = applications,
        )
    }

    private fun readApplication(
        client: DesfireClient,
        id: Int,
        dfName: ByteArray?,
        abort: (Throwable) -> Boolean,
    ): DesfireApplication {
        var locked = false
        val selected = attempt(abort) { client.selectApplication(id) }
        if (selected == null) {
            return DesfireApplication(id = id, dfName = dfName, locked = true)
        }

        val keySettings = attempt(abort) {
            client.getKeySettings()
        }
        val fileIds = attempt(abort) {
            client.getFileIds()
        }
        val isoFileIds = attempt(abort) {
            client.getIsoFileIds()
        }
        val files = fileIds?.take(MAX_FILES)?.map { fileId ->
            val settings = attempt(abort) { client.getFileSettings(fileId) }
            if (settings == null) {
                locked = true
                DesfireFile(id = fileId, type = DesfireFileType.UNKNOWN)
            } else {
                settings
            }
        }

        attempt(abort) { client.selectApplication(0) }

        return DesfireApplication(
            id = id,
            dfName = dfName,
            keySettings = keySettings?.settings,
            keyCount = keySettings?.keyCount,
            fileIds = fileIds,
            isoFileIds = isoFileIds,
            files = files,
            locked = locked,
        )
    }

    /** Null when the card refused the command; tag loss is rethrown. */
    private inline fun <T> attempt(
        abort: (Throwable) -> Boolean,
        block: () -> T,
    ): T? = try {
        block()
    } catch (error: Throwable) {
        if (abort(error)) throw error
        null
    }
}
