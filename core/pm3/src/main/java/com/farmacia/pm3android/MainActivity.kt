// pm3-android — Proxmark3 tools for Android.
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.farmacia.pm3android.ui.Pm3App

class MainActivity : ComponentActivity() {

    private val requestedTab = mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A saved instance state restores the selected tab inside Pm3App; only
        // a fresh launch should apply the static-shortcut target.
        if (savedInstanceState == null) {
            requestedTab.value = tabFor(intent)
        }
        setContent {
            Pm3App(
                requestedTab = requestedTab.value,
                onTabRequestHandled = { requestedTab.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTab.value = tabFor(intent)
    }

    private fun tabFor(intent: Intent?): Int? = when (intent?.action) {
        ACTION_SHORTCUT_CONSOLE -> 1
        ACTION_SHORTCUT_DEVICE -> 0
        else -> null
    }

    private companion object {
        const val ACTION_SHORTCUT_DEVICE = "com.farmacia.pm3android.SHORTCUT_DEVICE"
        const val ACTION_SHORTCUT_CONSOLE = "com.farmacia.pm3android.SHORTCUT_CONSOLE"
    }
}
