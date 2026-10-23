// pm3-android — Proxmark3 tools for a generic Chinese PM3 (CDC).
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.farmacia.pm3android.ui.Pm3App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Pm3App()
                }
            }
        }
    }
}
