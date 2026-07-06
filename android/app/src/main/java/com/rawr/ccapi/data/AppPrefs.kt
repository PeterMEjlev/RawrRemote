package com.rawr.ccapi.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * Tiny persistence for choices that should survive a restart: the connection
 * form, the SAF download destination, grid density, and sort order.
 *
 * Plain SharedPreferences on purpose — the values are a handful of strings, so
 * a DataStore dependency buys nothing here. The CCAPI password is deliberately
 * NOT persisted (it would sit in plain text; retyping it is the safer default).
 */
object AppPrefs {

    private const val FILE = "prefs"

    data class Saved(
        val host: String?,
        val port: String?,
        val username: String?,
        val destinationUri: Uri?,
        val destinationLabel: String?,
        val gridColumns: Int,
        val sortKey: String?,
        val sortAscending: Boolean?,
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): Saved {
        val p = prefs(context)
        return Saved(
            host = p.getString("host", null),
            port = p.getString("port", null),
            username = p.getString("username", null),
            destinationUri = p.getString("destUri", null)?.let(Uri::parse),
            destinationLabel = p.getString("destLabel", null),
            gridColumns = p.getInt("gridColumns", 0), // 0 = not set
            sortKey = p.getString("sortKey", null),
            sortAscending = if (p.contains("sortAsc")) p.getBoolean("sortAsc", true) else null,
        )
    }

    fun saveConnection(context: Context, host: String, port: String, username: String) {
        prefs(context).edit()
            .putString("host", host)
            .putString("port", port)
            .putString("username", username)
            .apply()
    }

    fun saveDestination(context: Context, uri: Uri, label: String) {
        prefs(context).edit()
            .putString("destUri", uri.toString())
            .putString("destLabel", label)
            .apply()
    }

    fun saveGridColumns(context: Context, columns: Int) {
        prefs(context).edit().putInt("gridColumns", columns).apply()
    }

    fun saveSort(context: Context, key: String, ascending: Boolean) {
        prefs(context).edit()
            .putString("sortKey", key)
            .putBoolean("sortAsc", ascending)
            .apply()
    }
}
