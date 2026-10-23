// Settings / about screen: application info and GPL notice. Client-side
// preferences live in $HOME/.proxmark3/prefs.json (managed by the client
// through its own "pref" console commands).
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Impostazioni", style = MaterialTheme.typography.titleLarge)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("pm3-android", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Client Proxmark3 integrato (nessun processo esterno), " +
                        "trasporto USB CDC diretto, firmware PM3GENERIC/PM3EASY 512k.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Licenza", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Questo programma è software libero: puoi ridistribuirlo e/o " +
                        "modificarlo secondo i termini della GNU General Public License " +
                        "versione 3. Insieme al client Proxmark3 (Iceman fork, GPLv3), " +
                        "condividendo per costruzione la stessa base di codice.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Cartelle client", style = MaterialTheme.typography.titleMedium)
                Text(
                    fontFamily = FontFamily.Monospace,
                    text = "HOME = filesDir\n" +
                        "\$HOME/.proxmark3/dictionaries\n" +
                        "\$HOME/.proxmark3/lualibs\n" +
                        "\$HOME/.proxmark3/luascripts\n" +
                        "\$HOME/.proxmark3/cmdscripts\n" +
                        "\$HOME/.proxmark3/resources\n" +
                        "\$HOME/.proxmark3/prefs.json",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
