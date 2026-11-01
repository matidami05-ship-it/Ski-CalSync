package com.ski.calsync.sync

import com.ski.calsync.ical.DAY_MS
import com.ski.calsync.ical.ICal
import com.ski.calsync.ical.ICalTime
import com.ski.calsync.ical.VEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A row from Android's Events table (what Samsung Calendar reads and writes). */
data class EventRow(
    val id: Long,
    val calendarId: Long,
    val syncId: String?,
    val etag: String?,
    val uid: String?,
    val dirty: Boolean,
    val deleted: Boolean,
    val originalId: Long?,
    val originalInstanceTime: Long?,
    val title: String?,
    val description: String?,
    val location: String?,
    val dtstart: Long,
    val dtend: Long?,
    val duration: String?,
    val allDay: Boolean,
    val timezone: String?,
    val rrule: String?,
    val exdate: String?,
    val status: Int?,        // 0 tentative, 1 confirmed, 2 canceled
    val availability: Int?,  // 0 busy, 1 free
    val reminders: List<Int> = emptyList(),
)

/** Pure conversions between Android event rows and iCalendar — no Android APIs, so it's unit-tested. */
object EventMapper {
    const val STATUS_TENTATIVE = 0
    const val STATUS_CONFIRMED = 1
    const val STATUS_CANCELED = 2

    private val UTC_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun exdateColumn(list: List<ICalTime>): String? =
        list.takeIf { it.isNotEmpty() }?.joinToString(",") { UTC_FMT.format(Instant.ofEpochMilli(it.epochMs)) }

    /** Android EXDATE: "v1,v2" or "TZID;v1,v2" with values in UTC, local or date form. */
    fun parseExdateColumn(s: String?, tz: String?, allDay: Boolean): List<ICalTime> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split('\n').flatMap { line ->
            var zone = ICal.resolveZone(tz) ?: ZoneOffset.UTC
            var values = line
            val semi = line.indexOf(';')
            if (semi > 0) { ICal.resolveZone(line.substring(0, semi))?.let { zone = it }; values = line.substring(semi + 1) }
            values.split(',').mapNotNull { raw ->
                val v = raw.trim()
                runCatching {
                    val ms = when {
                        v.length == 8 -> LocalDate.parse(v, DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                        v.endsWith("Z") -> LocalDateTime.parse(v.dropLast(1), LOCAL).toInstant(ZoneOffset.UTC).toEpochMilli()
                        else -> LocalDateTime.parse(v, LOCAL).atZone(zone).toInstant().toEpochMilli()
                    }
                    ICalTime(ms, if (allDay) null else tz, allDay)
                }.getOrNull()
            }
        }
    }

    fun durationColumn(ms: Long, allDay: Boolean): String =
        if (allDay) "P${maxOf(1, ms / DAY_MS)}D" else "P${maxOf(0, ms / 1000)}S"

    fun rowToVEvent(row: EventRow, uid: String): VEvent {
        val tz = if (row.allDay) null else (row.timezone ?: "UTC")
        val start = ICalTime(row.dtstart, tz, row.allDay)
        val endMs = row.dtend ?: ICal.parseDurationMs(row.duration)?.let { row.dtstart + it }
        return VEvent(
            uid = uid,
            start = start,
            end = endMs?.let { ICalTime(it, tz, row.allDay) },
            summary = row.title,
            description = row.description,
            location = row.location,
            rrule = row.rrule?.takeIf { it.isNotBlank() && row.originalId == null },
            exdates = parseExdateColumn(row.exdate, tz, row.allDay),
            status = when (row.status) {
                STATUS_TENTATIVE -> "TENTATIVE"
                STATUS_CANCELED -> "CANCELLED"
                STATUS_CONFIRMED -> "CONFIRMED"
                else -> null
            },
            transparent = row.availability == 1,
            alarmMinutes = row.reminders.filter { it >= 0 }.distinct(),
        )
    }

    /**
     * Builds the .ics for a master event plus its single-occurrence changes.
     * Occurrences deleted on the phone (canceled exceptions) become EXDATEs.
     */
    fun buildIcs(
        master: EventRow,
        exceptions: List<EventRow>,
        uid: String,
        dtstamp: Instant = Instant.now(),
    ): String {
        val m = rowToVEvent(master, uid)
        val live = exceptions.filter { !it.deleted && it.originalInstanceTime != null }
        val cancelled = live.filter { it.status == STATUS_CANCELED }
        val exdates = m.exdates + cancelled.map { ICalTime(it.originalInstanceTime!!, m.start.tzid, m.start.allDay) }
        val overrides = live.filter { it.status != STATUS_CANCELED }.map { ex ->
            rowToVEvent(ex, uid).copy(recurrenceId = ICalTime(ex.originalInstanceTime!!, m.start.tzid, m.start.allDay))
        }
        val masterOut = m.copy(exdates = exdates.distinctBy { it.epochMs })
        return if (masterOut.rrule == null) ICal.serialize(masterOut, emptyList(), dtstamp)
        else ICal.serialize(masterOut, overrides, dtstamp)
    }

    /** Stable fingerprint of an event's content (ignores DTSTAMP). */
    fun fingerprint(master: EventRow, exceptions: List<EventRow>, uid: String): String {
        val ics = buildIcs(master, exceptions, uid, Instant.EPOCH)
        val d = java.security.MessageDigest.getInstance("SHA-1").digest(ics.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Safe file name for an event resource. */
    fun resourceName(uid: String): String =
        uid.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
            .joinToString("").take(120) + ".ics"
}
