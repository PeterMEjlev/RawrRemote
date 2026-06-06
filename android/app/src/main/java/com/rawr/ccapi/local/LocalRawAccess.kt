package com.rawr.ccapi.local

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Broad file-read access needed to scan the whole phone for RAW files.
 *
 * - API 30+ (Android 11): "All files access" (MANAGE_EXTERNAL_STORAGE), granted
 *   from a system Settings screen — not a normal runtime dialog. This is the
 *   only thing that lets us read `.CR3` files written by other apps anywhere on
 *   storage; on API 33+ the media permissions don't cover non-media files.
 * - API 26–29: the legacy READ_EXTERNAL_STORAGE runtime permission is enough.
 */
object LocalRawAccess {

    /** Legacy runtime permission requested below API 30; null when not needed. */
    val legacyPermission: String? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        } else {
            null
        }

    fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /**
     * Settings screen to grant "All files access" (API 30+). Try the
     * app-specific page first, falling back to the global list if the OEM
     * doesn't support the per-app deep link.
     */
    fun allFilesSettingsIntent(context: Context): Intent {
        val appSpecific = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        return if (appSpecific.resolveActivity(context.packageManager) != null) {
            appSpecific
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }
}
