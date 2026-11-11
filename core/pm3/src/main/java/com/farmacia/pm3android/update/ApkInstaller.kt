// Installs a downloaded APK through the system package installer. Android
// always asks the user for confirmation; this helper only prepares the intent
// and, when needed, opens the per-app "install unknown apps" setting.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

object ApkInstaller {

    /** Why a downloaded APK must not be offered to the system installer. */
    enum class Rejection { NOT_OUR_APP, NOT_NEWER, SIGNATURE_MISMATCH }

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /**
     * Checks the downloaded archive against the installed app: same package id,
     * a higher version code and the same signing certificate. Only the platform
     * signature rule would refuse a foreign APK, and only after the user already
     * confirmed the install.
     */
    fun check(context: Context, file: File): Rejection? {
        if (!file.isFile) return Rejection.NOT_OUR_APP
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: return Rejection.NOT_OUR_APP
        if (archive.packageName != context.packageName) return Rejection.NOT_OUR_APP

        val installed = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull()
        if (installed != null) {
            val archiveCode = versionCodeOf(archive)
            val installedCode = versionCodeOf(installed)
            if (archiveCode in 1 until installedCode) return Rejection.NOT_NEWER
        }

        val archiveCert = signerDigest(archive)
        val installedCert = installed?.let(::signerDigest)
        if (archiveCert == null || installedCert == null || archiveCert != installedCert) {
            return Rejection.SIGNATURE_MISMATCH
        }
        return null
    }

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun signerDigest(info: PackageInfo): String? {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val cert = signers?.firstOrNull()?.toByteArray() ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(cert)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns false when the user still has to grant the install permission;
     * [openInstallPermission] opens the right Android setting in that case.
     */
    fun install(context: Context, file: File): Boolean {
        if (check(context, file) != null) return false
        if (!canInstall(context)) return false
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    fun openInstallPermission(context: Context) {
        val appSetting = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(appSetting) }
            .recoverCatching { context.startActivity(fallback) }
    }
}
