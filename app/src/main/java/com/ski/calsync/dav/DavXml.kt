package com.ski.calsync.dav

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

const val NS_DAV = "DAV:"
const val NS_CALDAV = "urn:ietf:params:xml:ns:caldav"
const val NS_CS = "http://calendarserver.org/ns/"
const val NS_ICAL = "http://apple.com/ns/ical/"

/** One <response> from a 207 Multi-Status, with the props that came back 200 OK. */
data class DavResponse(val href: String, val status: Int?, val props: Map<String, Element>) {
    fun prop(ns: String, name: String): Element? = props["$ns|$name"]
    fun text(ns: String, name: String): String? = prop(ns, name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}

fun Element.children(ns: String? = null, name: String? = null): List<Element> {
    val out = ArrayList<Element>()
    val nl = childNodes
    for (i in 0 until nl.length) {
        val n = nl.item(i)
        if (n.nodeType == Node.ELEMENT_NODE) {
            val e = n as Element
            if ((ns == null || e.namespaceURI == ns) && (name == null || e.localName == name)) out.add(e)
        }
    }
    return out
}

fun Element.child(ns: String, name: String): Element? = children(ns, name).firstOrNull()

object DavXml {

    private fun statusCode(s: String?): Int? =
        s?.trim()?.split(' ')?.getOrNull(1)?.toIntOrNull()

    fun parseMultistatus(xml: String): List<DavResponse> {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.isExpandEntityReferences = false }
        val doc = f.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val root = doc.documentElement ?: return emptyList()
        return root.children(NS_DAV, "response").mapNotNull { r ->
            val href = r.child(NS_DAV, "href")?.textContent?.trim() ?: return@mapNotNull null
            val props = HashMap<String, Element>()
            for (ps in r.children(NS_DAV, "propstat")) {
                val code = statusCode(ps.child(NS_DAV, "status")?.textContent) ?: 200
                if (code !in 200..299) continue
                ps.child(NS_DAV, "prop")?.children()?.forEach { p ->
                    props["${p.namespaceURI}|${p.localName}"] = p
                }
            }
            DavResponse(href, statusCode(r.child(NS_DAV, "status")?.textContent), props)
        }
    }

    fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
