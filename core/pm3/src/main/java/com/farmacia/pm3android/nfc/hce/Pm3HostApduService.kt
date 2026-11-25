// Android HostApduService implementation delegating to [HceRegistry], plus
// Android 15+ (API 35+) processPollingFrames sniffer hook.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.hce

import android.nfc.cardemulation.HostApduService
import android.nfc.cardemulation.PollingFrame
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi

class Pm3HostApduService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return Type4TagEmulator.SW_WRONG_LENGTH
        return HceRegistry.handleCommandApdu(commandApdu)
    }

    override fun onDeactivated(reason: Int) {
        HceRegistry.onDeactivated()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun processPollingFrames(frames: List<PollingFrame>) {
        for (frame in frames) {
            val typeLabel = when (frame.type) {
                PollingFrame.POLLING_LOOP_TYPE_A -> "NFC-A"
                PollingFrame.POLLING_LOOP_TYPE_B -> "NFC-B"
                PollingFrame.POLLING_LOOP_TYPE_F -> "NFC-F"
                'V'.code -> "NFC-V"
                PollingFrame.POLLING_LOOP_TYPE_ON -> "RF ON"
                PollingFrame.POLLING_LOOP_TYPE_OFF -> "RF OFF"
                else -> "UNKNOWN (${frame.type.toChar()})"
            }
            HceRegistry.recordPollingFrame(
                frameType = typeLabel,
                data = frame.data,
                gain = frame.vendorSpecificGain,
                timestampUs = frame.timestamp,
            )
        }
    }
}
