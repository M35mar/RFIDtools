// A user-visible file produced by the Proxmark3 client.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.data

import java.io.File

data class Pm3File(
    val file: File,
    val relativePath: String,
    val size: Long,
    val lastModified: Long,
    val category: FileCategory,
)
