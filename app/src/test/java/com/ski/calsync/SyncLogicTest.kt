package com.ski.calsync

import com.ski.calsync.dav.DavXml
import com.ski.calsync.dav.NS_CALDAV
import com.ski.calsync.dav.NS_CS
import com.ski.calsync.dav.NS_DAV
import com.ski.calsync.ical.ICal
import com.ski.calsync.sync.EventMapper
import com.ski.calsync.sync.EventRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class SyncLogicTest {

    private val chicago = ZoneId.of("America/Chicago")

    private val appleIcs = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Apple Inc.//iOS 18.0//EN
        BEGIN:VTIMEZONE
        TZID:America/Chicago
        BEGIN:DAYLIGHT
        DTSTART:20070311T020000
        RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU
        TZOFFSETFROM:-0600
        TZOFFSETTO:-0500
        END:DAYLIGHT
        END:VTIMEZONE
        BEGIN:VEVENT
        UID:ABC-123
        DTSTART;TZID=America/Chicago:20261105T090000
        DTEND;TZID=America/Chicago:20261105T100000
        RRULE:FREQ=WEEKLY;BYDAY=TH
        EXDATE;TZID=America/Chicago:20261112T090000
        SUMMARY:Job site walk\, building B
        LOCATION:123 Main St
        DESCRIPTION:Bring the long ladder\nand the meter
        BEGIN:VALARM
        ACTION:DISPLAY
        TRIGGER:-PT30M
        END:VALARM
        END:VEVENT
        BEGIN:VEVENT
        UID:ABC-123
        RECURRENCE-ID;TZID=America/Chicago:20261119T090000
        DTSTART;TZID=America/Chicago:20261119T110000
        DTEND;TZID=America/Chicago:20261119T120000
        SUMMARY:Job site walk (moved)
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Test fun parsesAppleRecurringEvent() {
        val evs = ICal.parse(appleIcs, chicago)
        assertEquals(2, evs.size)
        val m = evs.first { it.recurrenceId == null }
        assertEquals("ABC-123", m.uid)
        assertEquals("Job site walk, building B", m.summary)
        assertEquals("Bring the long ladder\nand the meter", m.description)
        assertEquals("FREQ=WEEKLY;BYDAY=TH", m.rrule)
        assertEquals(listOf(30), m.alarmMinutes)
        assertEquals(ZonedDateTime.of(2026, 11, 5, 9, 0, 0, 0, chicago).toInstant().toEpochMilli(), m.start.epochMs)
        assertEquals(3_600_000L, m.endMs - m.start.epochMs)
        assertEquals(1, m.exdates.size)
        val o = evs.first { it.recurrenceId != null }
        assertEquals(ZonedDateTime.of(2026, 11, 19, 9, 0, 0, 0, chicago).toInstant().toEpochMilli(), o.recurrenceId!!.epochMs)
    }

    @Test fun allDayAndUtc() {
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nDTSTART;VALUE=DATE:20261225\r\nSUMMARY:Christmas\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:y\r\nDTSTART:20261101T150000Z\r\nDURATION:PT45M\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val (a, b) = ICal.parse(ics, chicago)
        assertTrue(a.start.allDay)
        assertEquals(Instant.parse("2026-12-25T00:00:00Z").toEpochMilli(), a.start.epochMs)
        assertEquals(86_400_000L, a.endMs - a.start.epochMs)
        assertEquals(Instant.parse("2026-11-01T15:00:00Z").toEpochMilli(), b.start.epochMs)
        assertEquals(45 * 60_000L, b.endMs - b.start.epochMs)
    }

    @Test fun foldedLinesAndOutlookZone() {
        val ics = "BEGIN:VEVENT\r\nUID:z\r\nDTSTART;TZID=\"Central Standard Time\":20261101T080000\r\n" +
            "SUMMARY:A very long title that keeps going and going so that the line gets fo\r\n lded by the server\r\nEND:VEVENT\r\n"
        val e = ICal.parse(ics, ZoneId.of("UTC")).single()
        assertEquals("A very long title that keeps going and going so that the line gets folded by the server", e.summary)
        assertEquals(ZonedDateTime.of(2026, 11, 1, 8, 0, 0, 0, chicago).toInstant().toEpochMilli(), e.start.epochMs)
    }

    @Test fun roundTripThroughSerializer() {
        val original = ICal.parse(appleIcs, chicago)
        val master = original.first { it.recurrenceId == null }
        val out = ICal.serialize(master, original.filter { it.recurrenceId != null })
        assertTrue(out.contains("BEGIN:VTIMEZONE"))
        assertTrue(out.contains("DTSTART;TZID=America/Chicago:20261105T090000"))
        assertTrue(out.contains("SUMMARY:Job site walk\\, building B"))
        assertTrue(out.lines().all { it.toByteArray().size <= 75 })
        val again = ICal.parse(out, ZoneId.of("UTC"))
        assertEquals(original.map { it.start.epochMs }.sorted(), again.map { it.start.epochMs }.sorted())
        assertEquals(master.exdates.map { it.epochMs }, again.first { it.recurrenceId == null }.exdates.map { it.epochMs })
        assertEquals(listOf(30), again.first { it.recurrenceId == null }.alarmMinutes)
    }

    @Test fun vtimezoneHasDstRules() {
        val tz = ICal.vtimezone(chicago).joinToString("\n")
        assertTrue(tz.contains("BEGIN:DAYLIGHT"))
        assertTrue(tz.contains("TZOFFSETTO:-0500"))
        assertTrue(tz.contains("BYMONTH=3"))
        assertTrue(tz.contains("BYMONTH=11"))
    }

    private fun row(
        id: Long = 1, dtstart: Long, dtend: Long? = null, duration: String? = null, allDay: Boolean = false,
        tz: String? = "America/Chicago", rrule: String? = null, exdate: String? = null, originalId: Long? = null,
        originalTime: Long? = null, status: Int? = 1, title: String? = "Dentist", reminders: List<Int> = listOf(10),
    ) = EventRow(id, 5, null, null, null, true, false, originalId, originalTime, title, null, null,
        dtstart, dtend, duration, allDay, tz, rrule, exdate, status, 0, reminders)

    @Test fun samsungRowToIcs() {
        val start = ZonedDateTime.of(2026, 11, 3, 14, 0, 0, 0, chicago).toInstant().toEpochMilli()
        val ics = EventMapper.buildIcs(row(dtstart = start, dtend = start + 1_800_000), emptyList(), "uid-1")
        val e = ICal.parse(ics, ZoneId.of("UTC")).single()
        assertEquals(start, e.start.epochMs)
        assertEquals(start + 1_800_000, e.endMs)
        assertEquals("Dentist", e.summary)
        assertEquals(listOf(10), e.alarmMinutes)
    }

    @Test fun samsungRecurringWithDeletedAndMovedOccurrence() {
        val start = ZonedDateTime.of(2026, 11, 2, 7, 0, 0, 0, chicago).toInstant().toEpochMilli()
        val week = 7 * 86_400_000L
        val master = row(id = 1, dtstart = start, duration = "P3600S", rrule = "FREQ=WEEKLY;BYDAY=MO", title = "Standup")
        val cancelled = row(id = 2, dtstart = start + week, dtend = start + week + 3_600_000, originalId = 1,
            originalTime = start + week, status = EventMapper.STATUS_CANCELED)
        val moved = row(id = 3, dtstart = start + 2 * week + 7_200_000, dtend = start + 2 * week + 10_800_000,
            originalId = 1, originalTime = start + 2 * week, title = "Standup (late)")
        val evs = ICal.parse(EventMapper.buildIcs(master, listOf(cancelled, moved), "u"), ZoneId.of("UTC"))
        val m = evs.first { it.recurrenceId == null }
        assertEquals(3_600_000L, m.endMs - m.start.epochMs)
        assertEquals(listOf(start + week), m.exdates.map { it.epochMs })
        val o = evs.single { it.recurrenceId != null }
        assertEquals(start + 2 * week, o.recurrenceId!!.epochMs)
        assertEquals("Standup (late)", o.summary)
        assertNull(o.rrule)
    }

    @Test fun allDayRowStaysAllDay() {
        val d = Instant.parse("2026-11-26T00:00:00Z").toEpochMilli()
        val ics = EventMapper.buildIcs(row(dtstart = d, dtend = d + 86_400_000, allDay = true, tz = "UTC"), emptyList(), "t")
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20261126"))
        assertTrue(ics.contains("DTEND;VALUE=DATE:20261127"))
    }

    @Test fun exdateColumnFormats() {
        val list = EventMapper.parseExdateColumn("America/Chicago;20261109T070000,20261116T070000", "America/Chicago", false)
        assertEquals(2, list.size)
        assertEquals(ZonedDateTime.of(2026, 11, 9, 7, 0, 0, 0, chicago).toInstant().toEpochMilli(), list[0].epochMs)
        assertEquals("20261109T130000Z", EventMapper.exdateColumn(list.take(1)))
        assertEquals(3_600_000L, ICal.parseDurationMs("P3600S"))
        assertEquals(-900_000L, ICal.parseDurationMs("-PT15M"))
        assertEquals("P1D", EventMapper.durationColumn(86_400_000L, true))
    }

    @Test fun fingerprintIgnoresTimestamp() {
        val r = row(dtstart = 1_800_000_000_000)
        assertEquals(EventMapper.fingerprint(r, emptyList(), "a"), EventMapper.fingerprint(r, emptyList(), "a"))
        assertTrue(EventMapper.fingerprint(r, emptyList(), "a") != EventMapper.fingerprint(r.copy(title = "x"), emptyList(), "a"))
    }

    @Test fun parsesICloudMultistatus() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <multistatus xmlns="DAV:">
              <response><href>/1234/calendars/home/</href>
                <propstat><prop>
                  <resourcetype xmlns="DAV:"><collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></resourcetype>
                  <displayname>Home</displayname>
                  <getctag xmlns="http://calendarserver.org/ns/">HwoQEgg7</getctag>
                </prop><status>HTTP/1.1 200 OK</status></propstat>
                <propstat><prop><calendar-color xmlns="http://apple.com/ns/ical/"/></prop><status>HTTP/1.1 404 Not Found</status></propstat>
              </response>
              <response><href>/1234/calendars/inbox/</href>
                <propstat><prop><resourcetype><collection/></resourcetype></prop><status>HTTP/1.1 200 OK</status></propstat>
              </response>
            </multistatus>"""
        val rs = DavXml.parseMultistatus(xml)
        assertEquals(2, rs.size)
        assertEquals("Home", rs[0].text(NS_DAV, "displayname"))
        assertEquals("HwoQEgg7", rs[0].text(NS_CS, "getctag"))
        assertNotNull(rs[0].prop(NS_DAV, "resourcetype")?.let { it.getElementsByTagNameNS(NS_CALDAV, "calendar").item(0) })
        assertNull(rs[0].prop("http://apple.com/ns/ical/", "calendar-color"))
    }
}
