package com.ski.calsync.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderClient
import android.content.Context
import android.util.Log
import com.ski.calsync.account.Accounts
import com.ski.calsync.dav.CalDavClient
import com.ski.calsync.dav.PreconditionFailedException
import com.ski.calsync.dav.RemoteCalendar
import com.ski.calsync.ical.ICal
import java.util.UUID

/**
 * One full sync:
 *  1. iCloud calendars ⇄ phone (two-way; Samsung Calendar shows and edits them).
 *  2. Chosen Samsung/phone calendars → an iCloud calendar named "Samsung" (so they show on the iPhone).
 */
class SyncEngine(
    private val ctx: Context,
    private val account: Account,
    provider: ContentProviderClient,
    private val client: CalDavClient,
    private val prefs: Prefs = Prefs(ctx),
) {
    private val local = LocalStore(provider, account)
    private val tag = "SkiCalSync"

    data class Stats(var pulled: Int = 0, var pushed: Int = 0, var deleted: Int = 0, var mirrored: Int = 0)
    val stats = Stats()

    fun run() {
        val am = AccountManager.get(ctx)
        val home = am.getUserData(account, Accounts.KEY_HOME)
            ?: client.discoverHome().also { am.setUserData(account, Accounts.KEY_HOME, it) }

        val remotes = client.listCalendars(home)
        val mirrorUrl = prefs.mirrorTargetUrl
        prefs.remoteCache = remotes.filter { it.url != mirrorUrl }

        if (!prefs.selectionInitialized) {
            prefs.selectedRemote = remotes.filter { it.url != mirrorUrl }.map { it.url }.toSet()
        }
        val wanted = remotes.filter { it.url in prefs.selectedRemote && it.url != mirrorUrl }

        // Drop phone copies of calendars that were unticked or deleted on iCloud.
        for (lc in local.calendars()) {
            if (wanted.none { it.url == lc.url }) local.deleteCalendar(lc.id)
        }

        for (rc in wanted) {
            val calId = local.ensureCalendar(rc)
            runCatching { syncCalendar(calId, rc) }
                .onFailure { if (it is com.ski.calsync.dav.AuthException) throw it; Log.w(tag, "Calendar ${rc.name} failed", it) }
        }

        MirrorSync(ctx, local, client, prefs).run(home)?.let { stats.mirrored = it }
    }

    private fun syncCalendar(calId: Long, rc: RemoteCalendar) {
        val pushed = if (rc.readOnly) revertLocalEdits(calId) else pushLocalChanges(calId, rc)

        val storedCtag = local.calendars().firstOrNull { it.id == calId }?.ctag
        if (pushed == 0 && rc.ctag != null && rc.ctag == storedCtag) return // nothing changed on iCloud

        val remote = client.listEtags(rc.url)
        val rows = local.rows(calId)
        val idx = local.index(rows)

        val toFetch = remote.filter { (href, etag) -> idx[href]?.etag != etag }.keys.toList()
        for (chunk in toFetch.chunked(40)) {
            for (ev in client.multiget(rc.url, chunk)) {
                val existing = idx[ev.href]
                if (existing?.dirty == true) continue // local edit waiting to go up; don't clobber
                val parsed = runCatching { ICal.parse(ev.data) }.getOrNull() ?: continue
                local.replace(calId, ev.href, ev.etag, parsed, existing?.id)
                stats.pulled++
            }
        }
        for ((href, row) in idx) {
            if (href !in remote && !row.dirty) { local.purge(row.id); stats.deleted++ }
        }
        local.setCtag(calId, runCatching { client.getCtag(rc.url) }.getOrNull() ?: rc.ctag)
    }

    /** Sends phone-side adds/edits/deletes to iCloud. Returns how many events went up. */
    private fun pushLocalChanges(calId: Long, rc: RemoteCalendar): Int {
        val rows = local.rows(calId)
        val exceptions = rows.filter { it.originalId != null }.groupBy { it.originalId!! }
        var count = 0

        for (m in rows.filter { it.originalId == null }) {
            val group = exceptions[m.id].orEmpty()
            if (!m.dirty && !m.deleted && group.none { it.dirty || it.deleted }) continue

            if (m.deleted) {
                m.syncId?.let { href ->
                    try { client.delete(href, m.etag) } catch (e: PreconditionFailedException) { /* changed on iCloud; it'll come back */ }
                }
                local.purge(m.id)
                count++
                continue
            }

            val uid = m.uid ?: UUID.randomUUID().toString()
            val isNew = m.syncId == null
            val href = m.syncId ?: (rc.url + EventMapper.resourceName(uid))
            val ics = EventMapper.buildIcs(m, group, uid)
            try {
                val etag = client.put(href, ics, ifMatch = m.etag?.takeIf { !isNew && it.isNotEmpty() }, createOnly = isNew)
                // Unknown etag → blank, so the next pull re-reads iCloud's copy.
                local.markClean(m.id, href, etag ?: "", uid)
            } catch (e: PreconditionFailedException) {
                // Edited on both sides: iCloud wins, pull will overwrite.
                local.markClean(m.id, href, "", uid)
            }
            for (ex in group) if (ex.deleted) local.purge(ex.id) else local.markClean(ex.id)
            count++
        }
        stats.pushed += count
        return count
    }

    /** Read-only (shared) calendar: throw away phone edits and re-pull. */
    private fun revertLocalEdits(calId: Long): Int {
        var n = 0
        for (r in local.rows(calId).filter { (it.dirty || it.deleted) && it.originalId == null }) {
            // Purged rows are re-downloaded; edited rows get a blank etag so the pull overwrites them.
            if (r.syncId == null || r.deleted) local.purge(r.id) else local.markClean(r.id, etag = "")
            n++
        }
        return n
    }
}
