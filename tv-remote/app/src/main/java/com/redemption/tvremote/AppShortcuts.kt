package com.redemption.tvremote

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * A launchable TV app. [target] is one of:
 * - `link:<url>`  opened on the TV through the Android TV remote protocol
 * - `pkg:<package>` launched via `market://launch?id=<package>`
 * - `sony:<uri>`  launched through Sony Bravia IP control (exact app list from the TV)
 */
data class AppShortcut(val name: String, val target: String, val color: Int, val iconUrl: String = "") {

    fun toJson() = JSONObject().put("name", name).put("target", target).put("color", color).put("icon", iconUrl)

    val initials: String
        get() = name.split(' ', '-', '+').filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

    companion object {
        fun fromJson(o: JSONObject) = AppShortcut(
            o.getString("name"), o.getString("target"), o.optInt("color", 0xFF3A3F4B.toInt()), o.optString("icon", ""),
        )

        /** Builds a target from what the user typed: a URL or a package name. */
        fun targetFor(input: String): String? {
            val s = input.trim()
            return when {
                s.contains("://") -> "link:$s"
                Regex("[A-Za-z][\\w]*(\\.[A-Za-z_][\\w]*)+").matches(s) -> "pkg:$s"
                else -> null
            }
        }
    }
}

object AppPresets {
    val all = listOf(
        AppShortcut("YouTube", "link:https://www.youtube.com", 0xFFFF0033.toInt()),
        AppShortcut("Netflix", "link:https://www.netflix.com/title", 0xFFE50914.toInt()),
        AppShortcut("Prime Video", "link:https://app.primevideo.com", 0xFF00A8E1.toInt()),
        AppShortcut("JioHotstar", "pkg:in.startv.hotstar", 0xFF6C2BD9.toInt()),
        AppShortcut("Sony LIV", "pkg:com.sonyliv", 0xFF2A2A72.toInt()),
        AppShortcut("ZEE5", "pkg:com.graymatrix.did", 0xFF8230C6.toInt()),
        AppShortcut("Apple TV", "pkg:com.apple.atve.androidtv.appletv", 0xFF3A3A3C.toInt()),
        AppShortcut("Spotify", "pkg:com.spotify.tv.android", 0xFF1DB954.toInt()),
        AppShortcut("YouTube Music", "pkg:com.google.android.youtube.tvmusic", 0xFFD80000.toInt()),
        AppShortcut("Plex", "pkg:com.plexapp.android", 0xFFE5A00D.toInt()),
        AppShortcut("Kodi", "pkg:org.xbmc.kodi", 0xFF17B2E7.toInt()),
        AppShortcut("Play Store", "pkg:com.android.vending", 0xFF01875F.toInt()),
    )
    val defaults = all.take(4)

    private val palette = intArrayOf(
        0xFF3B6FD8.toInt(), 0xFF8E44AD.toInt(), 0xFF16A085.toInt(), 0xFFD35400.toInt(),
        0xFFC0392B.toInt(), 0xFF2C7A7B.toInt(), 0xFF6D4C41.toInt(), 0xFF546E7A.toInt(),
    )
    fun colorFor(name: String) = palette[(name.hashCode() and 0x7FFFFFFF) % palette.size]
}

class AppStore(private val prefs: SharedPreferences) {
    fun load(): List<AppShortcut> {
        val raw = prefs.getString(KEY, null) ?: return AppPresets.defaults
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { AppShortcut.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(AppPresets.defaults)
    }

    fun save(list: List<AppShortcut>) {
        prefs.edit().putString(KEY, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
    }

    private companion object { const val KEY = "apps" }
}
