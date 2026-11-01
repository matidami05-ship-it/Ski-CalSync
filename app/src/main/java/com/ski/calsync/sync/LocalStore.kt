package com.ski.calsync.sync

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import com.ski.calsync.dav.RemoteCalendar
import com.ski.calsync.ical.VEvent
import java.util.TimeZone

/** Reads/writes Android's calendar database, which Samsung Calendar displays. */
class LocalStore(private val p: ContentProviderClient, private val account: Account) {

    data class LocalCal(val id: Long, val url: String?, val ctag: String?)
    data class Indexed(val id: Long, val etag: String?, val dirty: Boolean)

    private fun sa(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
        .build()

    private fun Cursor.str(c: String) = getColumnIndex(c).let { if (it < 0 || isNull(it)) null else getString(it) }
    private fun Cursor.lng(c: String) = getColumnIndex(c).let { if (it < 0 || isNull(it)) null else getLong(it) }
    private fun Cursor.int(c: String) = getColumnIndex(c).let { if (it < 0 || isNull(it)) null else getInt(it) }

    // ------------------------------------------------------------ calendars

    fun calendars(): List<LocalCal> {
        val out = ArrayList<LocalCal>()
        p.query(
            sa(Calendars.CONTENT_URI), arrayOf(Calendars._ID, Calendars._SYNC_ID, Calendars.CAL_SYNC1),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?", arrayOf(account.name, account.type), null,
        )?.use { c ->
            while (c.moveToNext()) out += LocalCal(c.lng(Calendars._ID)!!, c.str(Calendars._SYNC_ID), c.str(Calendars.CAL_SYNC1))
        }
        return out
    }

    fun ensureCalendar(rc: RemoteCalendar): Long {
        val v = ContentValues().apply {
            put(Calendars.NAME, rc.url)
            put(Calendars.CALENDAR_DISPLAY_NAME, rc.name)
            put(Calendars.CALENDAR_COLOR, rc.color ?: 0xFF1E7BE0.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, if (rc.readOnly) Calendars.CAL_ACCESS_READ else Calendars.CAL_ACCESS_OWNER)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.VISIBLE, 1)
        }
        calendars().firstOrNull { it.url == rc.url }?.let { existing ->
            p.update(sa(ContentUris.withAppendedId(Calendars.CONTENT_URI, existing.id)), v, null, null)
            return existing.id
        }
        v.put(Calendars.ACCOUNT_NAME, account.name)
        v.put(Calendars.ACCOUNT_TYPE, account.type)
        v.put(Calendars._SYNC_ID, rc.url)
        v.put(Calendars.OWNER_ACCOUNT, account.name)
        v.put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        v.put(Calendars.ALLOWED_REMINDERS, "${Reminders.METHOD_ALERT},${Reminders.METHOD_DEFAULT}")
        v.put(Calendars.ALLOWED_AVAILABILITY, "${Events.AVAILABILITY_BUSY},${Events.AVAILABILITY_FREE}")
        val uri = p.insert(sa(Calendars.CONTENT_URI), v) ?: error("Couldn't create calendar ${rc.name}")
        return ContentUris.parseId(uri)
    }

    fun deleteCalendar(id: Long) {
        p.delete(sa(ContentUris.withAppendedId(Calendars.CONTENT_URI, id)), null, null)
    }

    fun setCtag(calId: Long, ctag: String?) {
        p.update(sa(ContentUris.withAppendedId(Calendars.CONTENT_URI, calId)),
            ContentValues().apply { put(Calendars.CAL_SYNC1, ctag) }, null, null)
    }

    // ------------------------------------------------------------ events

    private val EVENT_COLS = arrayOf(
        Events._ID, Events.CALENDAR_ID, Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.UID_2445,
        Events.DIRTY, Events.DELETED, Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME,
        Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND, Events.DURATION,
        Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.RRULE, Events.EXDATE, Events.STATUS, Events.AVAILABILITY,
    )

    /** Every row of a calendar (any account), including rows the user deleted but we haven't synced yet. */
    fun rows(calId: Long, asSyncAdapter: Boolean = true): List<EventRow> {
        val out = ArrayList<EventRow>()
        val uri = if (asSyncAdapter) sa(Events.CONTENT_URI) else Events.CONTENT_URI
        p.query(uri, EVENT_COLS, "${Events.CALENDAR_ID}=?", arrayOf(calId.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.lng(Events._ID)!!
                out += EventRow(
                    id = id,
                    calendarId = calId,
                    syncId = c.str(Events._SYNC_ID),
                    etag = c.str(Events.SYNC_DATA1),
                    uid = c.str(Events.SYNC_DATA2) ?: c.str(Events.UID_2445),
                    dirty = (c.int(Events.DIRTY) ?: 0) != 0,
                    deleted = (c.int(Events.DELETED) ?: 0) != 0,
                    originalId = c.lng(Events.ORIGINAL_ID),
                    originalInstanceTime = c.lng(Events.ORIGINAL_INSTANCE_TIME),
                    title = c.str(Events.TITLE),
                    description = c.str(Events.DESCRIPTION),
                    location = c.str(Events.EVENT_LOCATION),
                    dtstart = c.lng(Events.DTSTART) ?: 0L,
                    dtend = c.lng(Events.DTEND),
                    duration = c.str(Events.DURATION),
                    allDay = (c.int(Events.ALL_DAY) ?: 0) != 0,
                    timezone = c.str(Events.EVENT_TIMEZONE),
                    rrule = c.str(Events.RRULE),
                    exdate = c.str(Events.EXDATE),
                    status = c.int(Events.STATUS),
                    availability = c.int(Events.AVAILABILITY),
                    reminders = reminders(id),
                )
            }
        }
        return out
    }

    private fun reminders(eventId: Long): List<Int> {
        val out = ArrayList<Int>()
        p.query(Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES), "${Reminders.EVENT_ID}=?",
            arrayOf(eventId.toString()), null)?.use { c ->
            while (c.moveToNext()) c.int(Reminders.MINUTES)?.let { out += if (it < 0) 15 else it }
        }
        return out
    }

    /** syncId (href) → master row, for events that already exist on iCloud. */
    fun index(rows: List<EventRow>): Map<String, Indexed> =
        rows.filter { it.originalId == null && it.syncId != null }
            .associate { it.syncId!! to Indexed(it.id, it.etag, it.dirty || it.deleted) }

    fun purge(id: Long) {
        // Exceptions first, then the master row.
        p.delete(sa(Events.CONTENT_URI), "${Events.ORIGINAL_ID}=?", arrayOf(id.toString()))
        p.delete(sa(ContentUris.withAppendedId(Events.CONTENT_URI, id)), null, null)
    }

    fun markClean(id: Long, href: String? = null, etag: String? = null, uid: String? = null) {
        val v = ContentValues().apply {
            put(Events.DIRTY, 0)
            href?.let { put(Events._SYNC_ID, it) }
            etag?.let { put(Events.SYNC_DATA1, it) }
            uid?.let { put(Events.SYNC_DATA2, it) }
        }
        p.update(sa(ContentUris.withAppendedId(Events.CONTENT_URI, id)), v, null, null)
    }

    /** Replaces (or inserts) an event and its overrides with what's on iCloud. */
    fun replace(calId: Long, href: String, etag: String, events: List<VEvent>, existingId: Long?) {
        existingId?.let { purge(it) }
        val master = events.firstOrNull { it.recurrenceId == null } ?: return
        val masterId = insert(calId, master, href, etag, null)
        if (master.rrule != null) {
            for (o in events.filter { it.recurrenceId != null }) insert(calId, o, href, null, masterId to master)
        }
    }

    private fun insert(calId: Long, e: VEvent, href: String?, etag: String?, parent: Pair<Long, VEvent>?): Long {
        val v = ContentValues()
        v.put(Events.CALENDAR_ID, calId)
        v.put(Events.TITLE, e.summary ?: "")
        v.put(Events.DESCRIPTION, e.description)
        v.put(Events.EVENT_LOCATION, e.location)
        v.put(Events.DTSTART, e.start.epochMs)
        v.put(Events.ALL_DAY, if (e.start.allDay) 1 else 0)
        v.put(Events.EVENT_TIMEZONE, if (e.start.allDay) "UTC" else (e.start.tzid ?: TimeZone.getDefault().id))
        if (e.rrule != null && parent == null) {
            v.put(Events.RRULE, e.rrule)
            v.put(Events.DURATION, EventMapper.durationColumn(e.endMs - e.start.epochMs, e.start.allDay))
            v.putNull(Events.DTEND)
            EventMapper.exdateColumn(e.exdates)?.let { v.put(Events.EXDATE, it) }
        } else {
            v.put(Events.DTEND, e.endMs)
        }
        v.put(Events.STATUS, when (e.status) {
            "TENTATIVE" -> Events.STATUS_TENTATIVE
            "CANCELLED" -> Events.STATUS_CANCELED
            else -> Events.STATUS_CONFIRMED
        })
        v.put(Events.AVAILABILITY, if (e.transparent) Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY)
        v.put(Events.HAS_ALARM, if (e.alarmMinutes.isNotEmpty()) 1 else 0)
        v.put(Events.SYNC_DATA2, e.uid)
        if (parent == null) {
            v.put(Events._SYNC_ID, href)
            v.put(Events.SYNC_DATA1, etag)
        } else {
            val (pid, pm) = parent
            v.put(Events.ORIGINAL_ID, pid)
            v.put(Events.ORIGINAL_SYNC_ID, href ?: pm.uid)
            v.put(Events.ORIGINAL_INSTANCE_TIME, e.recurrenceId!!.epochMs)
            v.put(Events.ORIGINAL_ALL_DAY, if (pm.start.allDay) 1 else 0)
        }
        val uri = p.insert(sa(Events.CONTENT_URI), v) ?: error("Couldn't save event")
        val id = ContentUris.parseId(uri)
        for (m in e.alarmMinutes) {
            p.insert(sa(Reminders.CONTENT_URI), ContentValues().apply {
                put(Reminders.EVENT_ID, id)
                put(Reminders.MINUTES, m)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })
        }
        return id
    }
}
