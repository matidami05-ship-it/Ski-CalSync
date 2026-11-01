package com.ski.calsync.ical

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneOffsetTransitionRule

const val DAY_MS = 86_400_000L

/**
 * A point in time as calendars see it.
 * - allDay: epochMs is midnight UTC of that date, tzid is null.
 * - timed: epochMs is the real instant; tzid is the zone it was written in ("UTC" for Z times).
 */
data class ICalTime(val epochMs: Long, val tzid: String?, val allDay: Boolean)

data class VEvent(
    val uid: String,
    val start: ICalTime,
    val end: ICalTime? = null,
    val durationMs: Long? = null,
    val summary: String? = null,
    val description: String? = null,
    val location: String? = null,
    val rrule: String? = null,
    val exdates: List<ICalTime> = emptyList(),
    val recurrenceId: ICalTime? = null,
    val status: String? = null,        // TENTATIVE / CONFIRMED / CANCELLED
    val transparent: Boolean = false,  // true = shows as "free"
    val alarmMinutes: List<Int> = emptyList(),
) {
    val endMs: Long
        get() {
            val e = end?.epochMs ?: (start.epochMs + (durationMs ?: if (start.allDay) DAY_MS else 0L))
            // All-day events must last at least one day.
            return if (start.allDay && e <= start.epochMs) start.epochMs + DAY_MS else maxOf(e, start.epochMs)
        }
}

object ICal {

    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val UTC_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    // ---------------------------------------------------------------- parsing

    internal data class Prop(val name: String, val params: Map<String, String>, val value: String)

    internal fun unfold(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) {
                out[out.size - 1] = out.last() + raw.substring(1)
            } else if (raw.isNotEmpty()) {
                out.add(raw)
            }
        }
        return out
    }

    internal fun parseLine(line: String): Prop? {
        var inQuote = false
        var colon = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ':' && !inQuote) { colon = i; break }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        inQuote = false
        for (c in head) {
            when {
                c == '"' -> { inQuote = !inQuote; sb.append(c) }
                c == ';' && !inQuote -> { parts.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
        }
        parts.add(sb.toString())
        val params = HashMap<String, String>()
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            if (eq > 0) params[p.substring(0, eq).uppercase()] = p.substring(eq + 1).trim('"')
        }
        return Prop(parts[0].uppercase(), params, value)
    }

    fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")

    private val WINDOWS_ZONES = mapOf(
        "eastern standard time" to "America/New_York",
        "central standard time" to "America/Chicago",
        "mountain standard time" to "America/Denver",
        "us mountain standard time" to "America/Phoenix",
        "pacific standard time" to "America/Los_Angeles",
        "alaskan standard time" to "America/Anchorage",
        "hawaiian standard time" to "Pacific/Honolulu",
        "gmt standard time" to "Europe/London",
        "w. europe standard time" to "Europe/Berlin",
        "romance standard time" to "Europe/Paris",
        "utc" to "UTC",
    )

    /** Turns a TZID into a Java zone, coping with Outlook names and "/vendor/path/Region/City" ids. */
    fun resolveZone(tzid: String?): ZoneId? {
        if (tzid.isNullOrBlank()) return null
        val id = tzid.trim()
        runCatching { return ZoneId.of(id) }
        WINDOWS_ZONES[id.lowercase()]?.let { return ZoneId.of(it) }
        val segs = id.split('/').filter { it.isNotEmpty() }
        for (i in 1 until segs.size) {
            runCatching { return ZoneId.of(segs.drop(i).joinToString("/")) }
        }
        return null
    }

    fun parseTime(value: String, params: Map<String, String>, defaultZone: ZoneId): ICalTime? = runCatching {
        val v = value.trim()
        if (params["VALUE"] == "DATE" || v.length == 8) {
            val d = LocalDate.parse(v.take(8), DATE)
            ICalTime(d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), null, true)
        } else if (v.endsWith("Z")) {
            val ldt = LocalDateTime.parse(v.dropLast(1).padEnd(15, '0'), LOCAL)
            ICalTime(ldt.toInstant(ZoneOffset.UTC).toEpochMilli(), "UTC", false)
        } else {
            val zone = resolveZone(params["TZID"]) ?: defaultZone
            val ldt = LocalDateTime.parse(v.padEnd(15, '0'), LOCAL)
            ICalTime(ldt.atZone(zone).toInstant().toEpochMilli(), zone.id, false)
        }
    }.getOrNull()

    private val DUR = Regex("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$")

    /** RFC 5545 duration, also accepting Android's "P3600S" style. */
    fun parseDurationMs(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val m = DUR.matchEntire(s.trim().uppercase()) ?: return null
        fun g(i: Int) = m.groupValues[i].ifEmpty { "0" }.toLong()
        val ms = (((g(2) * 7 + g(3)) * 24 + g(4)) * 60 + g(5)) * 60_000 + g(6) * 1000
        return if (m.groupValues[1] == "-") -ms else ms
    }

    fun parse(text: String, defaultZone: ZoneId = ZoneId.systemDefault()): List<VEvent> {
        val events = ArrayList<VEvent>()
        var props: MutableList<Prop>? = null
        var alarms: MutableList<MutableList<Prop>>? = null
        var alarm: MutableList<Prop>? = null
        var skipDepth = 0 // nested components inside VEVENT we don't care about

        for (line in unfold(text)) {
            val p = parseLine(line) ?: continue
            when {
                p.name == "BEGIN" && p.value.equals("VEVENT", true) && props == null -> {
                    props = ArrayList(); alarms = ArrayList()
                }
                p.name == "BEGIN" && p.value.equals("VALARM", true) && props != null -> {
                    alarm = ArrayList()
                }
                p.name == "END" && p.value.equals("VALARM", true) && alarm != null -> {
                    alarms!!.add(alarm); alarm = null
                }
                p.name == "BEGIN" && props != null -> skipDepth++
                p.name == "END" && props != null && skipDepth > 0 -> skipDepth--
                p.name == "END" && p.value.equals("VEVENT", true) && props != null -> {
                    buildEvent(props, alarms!!, defaultZone)?.let(events::add)
                    props = null; alarms = null
                }
                alarm != null -> alarm!!.add(p)
                props != null && skipDepth == 0 -> props!!.add(p)
            }
        }
        return events
    }

    private fun buildEvent(props: List<Prop>, alarms: List<List<Prop>>, zone: ZoneId): VEvent? {
        fun first(n: String) = props.firstOrNull { it.name == n }
        val start = first("DTSTART")?.let { parseTime(it.value, it.params, zone) } ?: return null
        val exdates = props.filter { it.name == "EXDATE" }.flatMap { p ->
            p.value.split(',').mapNotNull { parseTime(it, p.params, zone) }
        }
        val alarmMinutes = alarms.mapNotNull { a ->
            val trig = a.firstOrNull { it.name == "TRIGGER" } ?: return@mapNotNull null
            if (trig.params["VALUE"] == "DATE-TIME" || trig.params["RELATED"] == "END") return@mapNotNull null
            val ms = parseDurationMs(trig.value) ?: return@mapNotNull null
            (-ms / 60_000).toInt().takeIf { it >= 0 }
        }.distinct()

        return VEvent(
            uid = first("UID")?.value?.trim() ?: java.util.UUID.randomUUID().toString(),
            start = start,
            end = first("DTEND")?.let { parseTime(it.value, it.params, zone) },
            durationMs = parseDurationMs(first("DURATION")?.value),
            summary = first("SUMMARY")?.value?.let(::unescape),
            description = first("DESCRIPTION")?.value?.let(::unescape),
            location = first("LOCATION")?.value?.let(::unescape),
            rrule = first("RRULE")?.value?.trim()?.takeIf { it.isNotEmpty() },
            exdates = exdates,
            recurrenceId = first("RECURRENCE-ID")?.let { parseTime(it.value, it.params, zone) },
            status = first("STATUS")?.value?.trim()?.uppercase(),
            transparent = first("TRANSP")?.value?.trim().equals("TRANSPARENT", true),
            alarmMinutes = alarmMinutes,
        )
    }

    // ---------------------------------------------------------------- writing

    fun formatTime(name: String, t: ICalTime): String = when {
        t.allDay -> "$name;VALUE=DATE:" + DATE.format(Instant.ofEpochMilli(t.epochMs).atZone(ZoneOffset.UTC))
        t.tzid == null || t.tzid == "UTC" || resolveZone(t.tzid) == null ->
            "$name:" + UTC_FMT.format(Instant.ofEpochMilli(t.epochMs))
        else -> {
            val z = resolveZone(t.tzid)!!
            "$name;TZID=${z.id}:" + LOCAL.format(Instant.ofEpochMilli(t.epochMs).atZone(z))
        }
    }

    /** Builds a VCALENDAR with the master event and any single-occurrence overrides. */
    fun serialize(
        master: VEvent,
        overrides: List<VEvent> = emptyList(),
        dtstamp: Instant = Instant.now(),
    ): String {
        val lines = ArrayList<String>()
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:-//Ski//CalSync 1.0//EN"
        lines += "CALSCALE:GREGORIAN"

        val zones = (listOf(master) + overrides)
            .flatMap { listOf(it.start, it.end, it.recurrenceId) }
            .filterNotNull()
            .filter { !it.allDay && it.tzid != null && it.tzid != "UTC" }
            .mapNotNull { resolveZone(it.tzid) }
            .distinct()
        for (z in zones) lines += vtimezone(z)

        lines += eventLines(master, master, dtstamp)
        for (o in overrides) lines += eventLines(o.copy(uid = master.uid, rrule = null, exdates = emptyList()), master, dtstamp)

        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }

    private fun eventLines(e: VEvent, master: VEvent, dtstamp: Instant): List<String> {
        val l = ArrayList<String>()
        l += "BEGIN:VEVENT"
        l += "UID:${e.uid}"
        l += "DTSTAMP:" + UTC_FMT.format(dtstamp)
        e.recurrenceId?.let { l += formatTime("RECURRENCE-ID", it.copy(allDay = master.start.allDay, tzid = master.start.tzid)) }
        l += formatTime("DTSTART", e.start)
        l += formatTime("DTEND", ICalTime(e.endMs, e.start.tzid, e.start.allDay))
        e.rrule?.let { l += "RRULE:$it" }
        for (x in e.exdates) l += formatTime("EXDATE", x.copy(allDay = e.start.allDay, tzid = e.start.tzid))
        e.summary?.takeIf { it.isNotEmpty() }?.let { l += "SUMMARY:" + escape(it) }
        e.location?.takeIf { it.isNotEmpty() }?.let { l += "LOCATION:" + escape(it) }
        e.description?.takeIf { it.isNotEmpty() }?.let { l += "DESCRIPTION:" + escape(it) }
        e.status?.let { l += "STATUS:$it" }
        l += "TRANSP:" + if (e.transparent) "TRANSPARENT" else "OPAQUE"
        for (m in e.alarmMinutes) {
            l += "BEGIN:VALARM"
            l += "ACTION:DISPLAY"
            l += "DESCRIPTION:Reminder"
            l += "TRIGGER:" + if (m == 0) "PT0S" else "-PT${m}M"
            l += "END:VALARM"
        }
        l += "END:VEVENT"
        return l
    }

    /** Folds a content line at 75 octets without splitting UTF-8 characters. */
    internal fun fold(line: String): String {
        if (line.toByteArray().size <= 75) return line
        val sb = StringBuilder()
        var count = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val chars = Character.charCount(cp)
            val bytes = String(Character.toChars(cp)).toByteArray().size
            if (count + bytes > limit) {
                sb.append("\r\n ")
                count = 0
                limit = 74
            }
            sb.appendCodePoint(cp)
            count += bytes
            i += chars
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- VTIMEZONE

    private fun offset(o: ZoneOffset): String {
        val s = o.totalSeconds
        val sign = if (s < 0) "-" else "+"
        val a = Math.abs(s)
        return "%s%02d%02d".format(sign, a / 3600, (a % 3600) / 60)
    }

    private val DOW = mapOf(
        DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
        DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
    )

    private fun ruleRRule(r: ZoneOffsetTransitionRule): String {
        val month = r.month.value
        val ind = r.dayOfMonthIndicator
        val dow = r.dayOfWeek
        return when {
            dow == null -> "FREQ=YEARLY;BYMONTH=$month;BYMONTHDAY=$ind"
            ind == -1 -> "FREQ=YEARLY;BYMONTH=$month;BYDAY=-1${DOW[dow]}"
            ind < 0 -> "FREQ=YEARLY;BYMONTH=$month;BYDAY=${DOW[dow]};BYMONTHDAY=" +
                (ind - 6..ind).joinToString(",")
            else -> "FREQ=YEARLY;BYMONTH=$month;BYDAY=${DOW[dow]};BYMONTHDAY=" +
                (ind..ind + 6).filter { it <= 31 }.joinToString(",")
        }
    }

    fun vtimezone(zone: ZoneId): List<String> {
        val rules = zone.rules
        val l = ArrayList<String>()
        l += "BEGIN:VTIMEZONE"
        l += "TZID:${zone.id}"
        val tr = rules.transitionRules
        if (tr.isEmpty()) {
            val o = rules.getOffset(Instant.now())
            l += "BEGIN:STANDARD"
            l += "DTSTART:19700101T000000"
            l += "TZOFFSETFROM:${offset(o)}"
            l += "TZOFFSETTO:${offset(o)}"
            l += "END:STANDARD"
        } else {
            for (r in tr) {
                val t = r.createTransition(1970)
                val kind = if (t.offsetAfter.totalSeconds > t.offsetBefore.totalSeconds) "DAYLIGHT" else "STANDARD"
                l += "BEGIN:$kind"
                l += "DTSTART:" + LOCAL.format(t.dateTimeBefore)
                l += "TZOFFSETFROM:${offset(t.offsetBefore)}"
                l += "TZOFFSETTO:${offset(t.offsetAfter)}"
                l += "RRULE:" + ruleRRule(r)
                l += "END:$kind"
            }
        }
        l += "END:VTIMEZONE"
        return l
    }
}
