package com.ski.calsync.sync

import android.content.Context
import android.util.Log
import com.ski.calsync.dav.CalDavClient

/**
 * Copies the phone calendars the user picked (Samsung account, Google, "My calendar"…)
 * into one iCloud calendar called "Samsung", so they appear in Apple Calendar.
 * Changes flow phone → iCloud; that calendar is hidden from the phone to avoid duplicates.
 */
class MirrorSync(
    private val ctx: Context,
    private val local: LocalStore,
    private val client: CalDavClient,
    private val prefs: Prefs,
) {
    companion object {
        const val CALENDAR_NAME = "Samsung"
        const val CALENDAR_SLUG = "ski-samsung-mirror/"
        const val COLOR = "#1259C3FF"
    }

    /** Returns number of events uploaded or removed, or null if mirroring is off. */
    fun run(home: String): Int? {
        val ids = prefs.mirrorCalendarIds
        val map = prefs.mirrorMap.toMutableMap()
        if (ids.isEmpty() && map.isEmpty()) return null

        var target = prefs.mirrorTargetUrl
        if (target == null) {
            target = home.trimEnd('/') + "/" + CALENDAR_SLUG
            client.makeCalendar(target, CALENDAR_NAME, COLOR)
            prefs.mirrorTargetUrl = target
        }

        var changed = 0
        val seen = HashSet<String>()
        for (calId in ids) {
            val all = runCatching { local.rows(calId, asSyncAdapter = false) }
            if (all.isFailure) {
                Log.w("SkiCalSync", "Can't read calendar $calId", all.exceptionOrNull())
                seen += map.keys.filter { it.startsWith("$calId:") } // keep its iCloud copies
                continue
            }
            val rows = all.getOrThrow().filter { !it.deleted }
            val exceptions = rows.filter { it.originalId != null }.groupBy { it.originalId!! }

            for (m in rows.filter { it.originalId == null }) {
                val key = "$calId:${m.id}"
                seen += key
                val uid = m.uid?.takeIf { it.isNotBlank() } ?: "ski-samsung-$calId-${m.id}@calsync"
                val group = exceptions[m.id].orEmpty()
                val hash = EventMapper.fingerprint(m, group, uid)
                val prev = map[key]
                if (prev?.hash == hash) continue
                val href = prev?.href ?: (target + EventMapper.resourceName("samsung-$calId-${m.id}"))
                runCatching {
                    client.put(href, EventMapper.buildIcs(m, group, uid))
                    map[key] = Prefs.Mirrored(href, hash)
                    changed++
                }.onFailure { Log.w("SkiCalSync", "Mirror upload failed for $key", it) }
            }
        }

        for (key in map.keys - seen) {
            runCatching { client.delete(map[key]!!.href) }
                .onSuccess { map.remove(key); changed++ }
                .onFailure { Log.w("SkiCalSync", "Mirror delete failed for $key", it) }
        }

        prefs.mirrorMap = map
        return changed
    }
}
