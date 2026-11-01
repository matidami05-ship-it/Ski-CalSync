package com.ski.calsync.sync

import android.content.Context
import android.content.SharedPreferences
import com.ski.calsync.dav.RemoteCalendar
import org.json.JSONArray
import org.json.JSONObject

/** Settings and small bits of sync state. */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("calsync", Context.MODE_PRIVATE)

    /** iCloud calendar URLs shown (two-way) in Samsung Calendar. */
    var selectedRemote: Set<String>
        get() = sp.getStringSet("selected_remote", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("selected_remote", v).putBoolean("selected_init", true).apply()

    val selectionInitialized: Boolean get() = sp.getBoolean("selected_init", false)

    /** Phone calendar IDs (Samsung / Google / local) copied up to iCloud. */
    var mirrorCalendarIds: Set<Long>
        get() = sp.getStringSet("mirror_ids", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = sp.edit().putStringSet("mirror_ids", v.map { it.toString() }.toSet()).apply()

    /** The iCloud calendar that receives Samsung events. */
    var mirrorTargetUrl: String?
        get() = sp.getString("mirror_target", null)
        set(v) = sp.edit().putString("mirror_target", v).apply()

    data class Mirrored(val href: String, val hash: String)

    var mirrorMap: Map<String, Mirrored>
        get() {
            val o = runCatching { JSONObject(sp.getString("mirror_map", "{}")!!) }.getOrDefault(JSONObject())
            return o.keys().asSequence().associateWith { k ->
                val e = o.getJSONObject(k); Mirrored(e.getString("h"), e.getString("x"))
            }
        }
        set(v) {
            val o = JSONObject()
            v.forEach { (k, m) -> o.put(k, JSONObject().put("h", m.href).put("x", m.hash)) }
            sp.edit().putString("mirror_map", o.toString()).apply()
        }

    var remoteCache: List<RemoteCalendar>
        get() {
            val a = runCatching { JSONArray(sp.getString("remote_cache", "[]")!!) }.getOrDefault(JSONArray())
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                RemoteCalendar(
                    url = o.getString("url"),
                    name = o.getString("name"),
                    color = if (o.has("color")) o.getInt("color") else null,
                    ctag = null,
                    readOnly = o.optBoolean("ro"),
                )
            }
        }
        set(v) {
            val a = JSONArray()
            v.forEach { c ->
                a.put(JSONObject().put("url", c.url).put("name", c.name).put("ro", c.readOnly)
                    .apply { c.color?.let { put("color", it) } })
            }
            sp.edit().putString("remote_cache", a.toString()).apply()
        }

    var lastSyncMs: Long
        get() = sp.getLong("last_sync", 0)
        set(v) = sp.edit().putLong("last_sync", v).apply()

    var lastStatus: String
        get() = sp.getString("last_status", "") ?: ""
        set(v) = sp.edit().putString("last_status", v).apply()

    var lastOk: Boolean
        get() = sp.getBoolean("last_ok", true)
        set(v) = sp.edit().putBoolean("last_ok", v).apply()

    fun clear() = sp.edit().clear().apply()
}
