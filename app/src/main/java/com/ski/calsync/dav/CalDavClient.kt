package com.ski.calsync.dav

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class AuthException(msg: String) : IOException(msg)
class PreconditionFailedException(msg: String) : IOException(msg)
class DavException(val code: Int, msg: String) : IOException(msg)

data class RemoteCalendar(
    val url: String,
    val name: String,
    val color: Int?,
    val ctag: String?,
    val readOnly: Boolean,
)

data class RemoteEvent(val href: String, val etag: String, val data: String)

/** Minimal CalDAV client for iCloud (works with any CalDAV server). Blocking — call off the main thread. */
class CalDavClient(username: String, password: String) {

    companion object {
        const val ICLOUD_ROOT = "https://caldav.icloud.com/"
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val ICS = "text/calendar; charset=utf-8".toMediaType()
    }

    private val auth = Credentials.basic(username, password)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // Network interceptor so the header survives iCloud's cross-host redirects.
        .addNetworkInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", auth).build())
        }
        .build()

    private fun call(
        method: String,
        url: String,
        body: String? = null,
        depth: String? = null,
        headers: Map<String, String> = emptyMap(),
        type: okhttp3.MediaType = XML,
    ): Response {
        val b = Request.Builder().url(url)
            .header("User-Agent", "SkiCalSync/1.0")
            .method(method, body?.toRequestBody(type))
        depth?.let { b.header("Depth", it) }
        headers.forEach { (k, v) -> b.header(k, v) }
        val resp = http.newCall(b.build()).execute()
        if (resp.code == 401) {
            resp.close()
            throw AuthException("iCloud rejected the Apple ID or app-specific password")
        }
        return resp
    }

    private fun multistatus(method: String, url: String, body: String, depth: String): Pair<String, List<DavResponse>> {
        call(method, url, body, depth).use { r ->
            if (r.code != 207) throw DavException(r.code, "$method $url failed: HTTP ${r.code}")
            val base = r.request.url.toString()
            return base to DavXml.parseMultistatus(r.body!!.string())
        }
    }

    private fun resolve(base: String, href: String): String = base.toHttpUrl().resolve(href)!!.toString()

    private fun propfindHref(url: String, ns: String, prop: String, prefix: String): String {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:$prefix="$ns"><d:prop><$prefix:$prop/></d:prop></d:propfind>"""
        val (base, rs) = multistatus("PROPFIND", url, body, "0")
        val href = rs.firstNotNullOfOrNull { it.prop(ns, prop)?.child(NS_DAV, "href")?.textContent?.trim() }
            ?: throw DavException(0, "Server didn't return $prop")
        return resolve(base, href)
    }

    /** Signs in and returns the calendar home URL. */
    fun discoverHome(): String {
        val principal = propfindHref(ICLOUD_ROOT, NS_DAV, "current-user-principal", "x")
        return propfindHref(principal, NS_CALDAV, "calendar-home-set", "c")
    }

    fun listCalendars(home: String): List<RemoteCalendar> {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="$NS_CALDAV" xmlns:cs="$NS_CS" xmlns:ic="$NS_ICAL">
              <d:prop>
                <d:resourcetype/><d:displayname/><cs:getctag/><ic:calendar-color/>
                <c:supported-calendar-component-set/><d:current-user-privilege-set/>
              </d:prop>
            </d:propfind>"""
        val (base, rs) = multistatus("PROPFIND", home, body, "1")
        return rs.mapNotNull { r ->
            val rt = r.prop(NS_DAV, "resourcetype") ?: return@mapNotNull null
            if (rt.child(NS_CALDAV, "calendar") == null) return@mapNotNull null
            val comps = r.prop(NS_CALDAV, "supported-calendar-component-set")
                ?.children(NS_CALDAV, "comp")?.map { it.getAttribute("name").uppercase() }
            if (comps != null && comps.isNotEmpty() && "VEVENT" !in comps) return@mapNotNull null
            val privs = r.prop(NS_DAV, "current-user-privilege-set")
                ?.children(NS_DAV, "privilege")?.flatMap { p -> p.children().map { it.localName } }
            val readOnly = privs != null && privs.isNotEmpty() &&
                privs.none { it == "write" || it == "write-content" || it == "all" }
            val url = resolve(base, r.href)
            RemoteCalendar(
                url = if (url.endsWith("/")) url else "$url/",
                name = r.text(NS_DAV, "displayname") ?: "iCloud",
                color = parseColor(r.text(NS_ICAL, "calendar-color")),
                ctag = r.text(NS_CS, "getctag"),
                readOnly = readOnly,
            )
        }
    }

    fun getCtag(calUrl: String): String? {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:cs="$NS_CS"><d:prop><cs:getctag/></d:prop></d:propfind>"""
        return multistatus("PROPFIND", calUrl, body, "0").second.firstNotNullOfOrNull { it.text(NS_CS, "getctag") }
    }

    /** href → etag for every event in the calendar. */
    fun listEtags(calUrl: String): Map<String, String> {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <c:calendar-query xmlns:d="DAV:" xmlns:c="$NS_CALDAV">
              <d:prop><d:getetag/></d:prop>
              <c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT"/></c:comp-filter></c:filter>
            </c:calendar-query>"""
        val (base, rs) = multistatus("REPORT", calUrl, body, "1")
        val out = HashMap<String, String>()
        for (r in rs) {
            val href = resolve(base, r.href)
            if (href.trimEnd('/') == calUrl.trimEnd('/')) continue
            val etag = r.text(NS_DAV, "getetag") ?: continue
            out[href] = etag
        }
        return out
    }

    fun multiget(calUrl: String, hrefs: Collection<String>): List<RemoteEvent> {
        if (hrefs.isEmpty()) return emptyList()
        val hrefXml = hrefs.joinToString("") { "<d:href>${DavXml.xmlEscape(it.toHttpUrl().encodedPath)}</d:href>" }
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <c:calendar-multiget xmlns:d="DAV:" xmlns:c="$NS_CALDAV">
              <d:prop><d:getetag/><c:calendar-data/></d:prop>$hrefXml
            </c:calendar-multiget>"""
        val (base, rs) = multistatus("REPORT", calUrl, body, "1")
        return rs.mapNotNull { r ->
            val etag = r.text(NS_DAV, "getetag") ?: return@mapNotNull null
            val data = r.prop(NS_CALDAV, "calendar-data")?.textContent ?: return@mapNotNull null
            RemoteEvent(resolve(base, r.href), etag, data)
        }
    }

    /**
     * Uploads an event. ifMatch = known etag (update), createOnly = true for brand-new events.
     * Returns the new etag if the server sent one.
     */
    fun put(url: String, ics: String, ifMatch: String? = null, createOnly: Boolean = false): String? {
        val h = HashMap<String, String>()
        ifMatch?.let { h["If-Match"] = it }
        if (createOnly) h["If-None-Match"] = "*"
        call("PUT", url, ics, headers = h, type = ICS).use { r ->
            if (r.code == 412) throw PreconditionFailedException("Event changed on iCloud: $url")
            if (r.code !in 200..299) throw DavException(r.code, "PUT $url failed: HTTP ${r.code}")
            return r.header("ETag")
        }
    }

    fun delete(url: String, ifMatch: String? = null) {
        val h = ifMatch?.takeIf { it.isNotEmpty() }?.let { mapOf("If-Match" to it) } ?: emptyMap()
        call("DELETE", url, headers = h).use { r ->
            if (r.code == 412) throw PreconditionFailedException("Event changed on iCloud: $url")
            if (r.code !in 200..299 && r.code != 404) throw DavException(r.code, "DELETE $url failed: HTTP ${r.code}")
        }
    }

    /** Creates a calendar; fine if it already exists. */
    fun makeCalendar(url: String, name: String, colorHex: String) {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <c:mkcalendar xmlns:d="DAV:" xmlns:c="$NS_CALDAV" xmlns:ic="$NS_ICAL">
              <d:set><d:prop>
                <d:displayname>${DavXml.xmlEscape(name)}</d:displayname>
                <ic:calendar-color>$colorHex</ic:calendar-color>
                <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set>
              </d:prop></d:set>
            </c:mkcalendar>"""
        call("MKCALENDAR", url, body).use { r ->
            if (r.code !in 200..299 && r.code != 405) throw DavException(r.code, "Couldn't create calendar: HTTP ${r.code}")
        }
    }
}

/** "#RRGGBB" or "#RRGGBBAA" → ARGB int. */
fun parseColor(s: String?): Int? {
    val h = s?.trim()?.removePrefix("#") ?: return null
    if (h.length < 6) return null
    val rgb = h.substring(0, 6).toLongOrNull(16) ?: return null
    return (0xFF000000L or rgb).toInt()
}
