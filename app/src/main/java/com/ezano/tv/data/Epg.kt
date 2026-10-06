package com.ezano.tv.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone

/** In-memory electronic programme guide, keyed by normalized channel key. */
class EpgData(
    val byKey: Map<String, List<Programme>>,
    /** normalized display name -> key, to match channels without tvg-id. */
    val nameIndex: Map<String, String>,
    val loadedAt: Long,
) {
    val isEmpty get() = byKey.isEmpty()
    val programmeCount: Int by lazy { byKey.values.sumOf { it.size } }

    fun keyFor(channel: Channel): String? {
        if (channel.epgId.isNotBlank()) {
            val k = EpgParser.norm(channel.epgId)
            if (byKey.containsKey(k)) return k
        }
        val n = EpgParser.normName(channel.name)
        nameIndex[n]?.let { return it }
        return null
    }

    fun programmes(channel: Channel): List<Programme> = keyFor(channel)?.let { byKey[it] } ?: emptyList()

    fun now(channel: Channel, at: Long = System.currentTimeMillis()): Programme? =
        programmes(channel).let { list -> findAt(list, at) }

    fun next(channel: Channel, at: Long = System.currentTimeMillis()): Programme? =
        programmes(channel).firstOrNull { it.start >= at }

    fun window(channel: Channel, from: Long, to: Long): List<Programme> =
        programmes(channel).filter { it.stop > from && it.start < to }

    companion object {
        val EMPTY = EpgData(emptyMap(), emptyMap(), 0)

        fun findAt(list: List<Programme>, at: Long): Programme? {
            var lo = 0; var hi = list.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val p = list[mid]
                when {
                    at < p.start -> hi = mid - 1
                    at >= p.stop -> lo = mid + 1
                    else -> return p
                }
            }
            return null
        }
    }
}

object EpgParser {

    fun norm(id: String) = id.trim().lowercase()

    private val junk = Regex("\\b(hd|fhd|uhd|4k|sd|hevc|h265|backup|\\+1)\\b|[^a-z0-9]")
    fun normName(name: String): String =
        name.lowercase()
            .replace(Regex("^[a-z]{2,3}\\s*[:|]\\s*"), "") // "US: ", "UK | "
            .replace(junk, "")

    /**
     * Streams an XMLTV document. Only keeps programmes inside [keepFrom, keepTo] and,
     * if [wantedKeys] is non-empty, only for channels the playlist actually has.
     */
    fun parse(
        input: InputStream,
        keepFrom: Long,
        keepTo: Long,
        wantedKeys: Set<String>,
        wantedNames: Set<String>,
        shiftMs: Long,
    ): EpgData {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val byKey = HashMap<String, ArrayList<Programme>>(2048)
        val nameIndex = HashMap<String, String>(2048)
        val acceptedKeys = HashSet<String>()

        var curChannelId: String? = null
        var progKey: String? = null
        var progStart = 0L
        var progStop = 0L
        var title = ""
        var desc = ""
        var inProg = false
        var keepProg = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> curChannelId = parser.getAttributeValue(null, "id")?.let(::norm)
                    "display-name" -> if (curChannelId != null && !inProg) {
                        val dn = safeText(parser)
                        val n = normName(dn)
                        if (n.isNotEmpty()) {
                            if (wantedNames.isEmpty() || n in wantedNames) {
                                // (HashMap.putIfAbsent is API 24+; minSdk is 22)
                                if (!nameIndex.containsKey(n)) nameIndex[n] = curChannelId!!
                                acceptedKeys += curChannelId!!
                            }
                        }
                    }
                    "programme" -> {
                        inProg = true
                        title = ""; desc = ""
                        progKey = parser.getAttributeValue(null, "channel")?.let(::norm)
                        progStart = parseTime(parser.getAttributeValue(null, "start")) + shiftMs
                        progStop = parseTime(parser.getAttributeValue(null, "stop")) + shiftMs
                        val k = progKey
                        keepProg = k != null && progStart > 0 && progStop > keepFrom && progStart < keepTo &&
                            (wantedKeys.isEmpty() || k in wantedKeys || k in acceptedKeys)
                    }
                    "title" -> if (inProg && keepProg && title.isEmpty()) title = safeText(parser)
                    "desc" -> if (inProg && keepProg && desc.isEmpty()) desc = safeText(parser).take(400)
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "channel" -> curChannelId = null
                    "programme" -> {
                        if (keepProg && progKey != null) {
                            val stop = if (progStop > progStart) progStop else progStart + 30 * 60_000L
                            byKey.getOrPut(progKey!!) { ArrayList() } += Programme(progKey!!, progStart, stop, title, desc)
                        }
                        inProg = false; keepProg = false
                    }
                }
            }
            event = parser.next()
        }
        val sorted = HashMap<String, List<Programme>>(byKey.size)
        byKey.forEach { (k, v) ->
            v.sortBy { it.start }
            // de-duplicate overlapping duplicates
            val out = ArrayList<Programme>(v.size)
            for (p in v) if (out.isEmpty() || p.start >= out.last().stop || p.start != out.last().start) out += p
            sorted[k] = out
        }
        return EpgData(sorted, nameIndex, System.currentTimeMillis())
    }

    private fun safeText(parser: XmlPullParser): String = runCatching { parser.nextText().trim() }.getOrDefault("")

    /** XMLTV time: "yyyyMMddHHmmss +ZZZZ" (offset optional). */
    fun parseTime(s: String?): Long {
        if (s == null || s.length < 12) return 0
        return try {
            val t = s.trim()
            val y = t.substring(0, 4).toInt(); val mo = t.substring(4, 6).toInt(); val d = t.substring(6, 8).toInt()
            val h = t.substring(8, 10).toInt(); val mi = t.substring(10, 12).toInt()
            val sec = if (t.length >= 14 && t[12].isDigit()) t.substring(12, 14).toInt() else 0
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear(); cal.set(y, mo - 1, d, h, mi, sec)
            var ms = cal.timeInMillis
            val off = Regex("([+-])(\\d{2}):?(\\d{2})\\s*$").find(t)
            if (off != null) {
                val sign = if (off.groupValues[1] == "-") -1 else 1
                val offMs = (off.groupValues[2].toInt() * 60 + off.groupValues[3].toInt()) * 60_000L
                ms -= sign * offMs
            }
            ms
        } catch (e: Exception) { 0 }
    }

    // ---- Compact binary cache (much faster to reload than XML/JSON) ----

    fun save(file: File, data: EpgData) {
        runCatching {
            DataOutputStream(BufferedOutputStream(FileOutputStream(file), 256 * 1024)).use { out ->
                out.writeInt(3) // version
                out.writeLong(data.loadedAt)
                out.writeInt(data.nameIndex.size)
                data.nameIndex.forEach { (k, v) -> out.writeUTF(k); out.writeUTF(v) }
                out.writeInt(data.byKey.size)
                data.byKey.forEach { (k, list) ->
                    out.writeUTF(k)
                    out.writeInt(list.size)
                    list.forEach { p ->
                        out.writeLong(p.start); out.writeLong(p.stop)
                        out.writeUTF(p.title.take(300)); out.writeUTF(p.desc.take(400))
                    }
                }
            }
        }
    }

    fun load(file: File): EpgData? = runCatching {
        if (!file.exists()) return null
        DataInputStream(BufferedInputStream(FileInputStream(file), 256 * 1024)).use { inp ->
            if (inp.readInt() != 3) return null
            val loadedAt = inp.readLong()
            val nameCount = inp.readInt()
            val names = HashMap<String, String>(nameCount * 2)
            repeat(nameCount) { names[inp.readUTF()] = inp.readUTF() }
            val keyCount = inp.readInt()
            val byKey = HashMap<String, List<Programme>>(keyCount * 2)
            repeat(keyCount) {
                val k = inp.readUTF()
                val n = inp.readInt()
                val list = ArrayList<Programme>(n)
                repeat(n) { list += Programme(k, inp.readLong(), inp.readLong(), inp.readUTF(), inp.readUTF()) }
                byKey[k] = list
            }
            EpgData(byKey, names, loadedAt)
        }
    }.getOrNull()
}
