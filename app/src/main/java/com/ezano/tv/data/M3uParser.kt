package com.ezano.tv.data

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/** Fast, streaming M3U/M3U8 playlist parser. Splits entries into live, movies and series. */
object M3uParser {

    data class Result(val library: Library, val epgUrl: String)

    private val attrRegex = Regex("([\\w-]+)=\"([^\"]*)\"")
    private val seRegex = Regex("^(.*?)[\\s._-]*[Ss](\\d{1,2})[\\s._-]*[Ee](\\d{1,3})")
    private val vodExt = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm")

    fun parse(playlistId: String, input: InputStream): Result {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024)
        var epgUrl = ""
        val liveCats = LinkedHashMap<String, Category>()
        val movieCats = LinkedHashMap<String, Category>()
        val seriesCats = LinkedHashMap<String, Category>()
        val channels = ArrayList<Channel>(4096)
        val movies = ArrayList<Movie>(4096)
        val seriesMap = LinkedHashMap<String, Series>()
        val episodes = HashMap<String, MutableList<Episode>>()

        var attrs: Map<String, String> = emptyMap()
        var title = ""
        var group = ""
        var pendingUa = ""
        var count = 0

        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", true) -> {
                    val a = parseAttrs(line)
                    epgUrl = a["url-tvg"] ?: a["x-tvg-url"] ?: epgUrl
                    epgUrl = epgUrl.split(',').firstOrNull()?.trim() ?: ""
                }
                line.startsWith("#EXTINF", true) -> {
                    attrs = parseAttrs(line)
                    title = extinfTitle(line)
                    group = attrs["group-title"] ?: ""
                }
                line.startsWith("#EXTGRP:", true) -> if (group.isBlank()) group = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:http-user-agent=", true) -> pendingUa = line.substringAfter('=')
                line.startsWith("#") -> Unit
                else -> {
                    val url = line
                    val name = title.ifBlank { attrs["tvg-name"] ?: url.substringAfterLast('/') }
                    val logo = attrs["tvg-logo"] ?: ""
                    val grp = group.ifBlank { "Uncategorized" }
                    val lowerUrl = url.lowercase()
                    val ext = lowerUrl.substringBefore('?').substringAfterLast('.', "")
                    val se = seRegex.find(name)
                    val type = when {
                        lowerUrl.contains("/series/") -> ContentType.SERIES
                        lowerUrl.contains("/movie/") -> ContentType.MOVIE
                        ext in vodExt -> if (se != null) ContentType.SERIES else ContentType.MOVIE
                        else -> ContentType.LIVE
                    }
                    count++
                    when (type) {
                        ContentType.LIVE -> {
                            val cat = liveCats.getOrPut(grp) { Category("l${liveCats.size}", grp, ContentType.LIVE) }
                            channels += Channel(
                                id = "m$count",
                                number = attrs["tvg-chno"]?.toIntOrNull() ?: (channels.size + 1),
                                name = name,
                                logo = logo,
                                categoryId = cat.id,
                                url = url,
                                epgId = attrs["tvg-id"] ?: attrs["tvg-name"] ?: "",
                                catchupDays = attrs["catchup-days"]?.toIntOrNull() ?: attrs["timeshift"]?.toIntOrNull() ?: 0,
                            )
                        }
                        ContentType.MOVIE -> {
                            val cat = movieCats.getOrPut(grp) { Category("v${movieCats.size}", grp, ContentType.MOVIE) }
                            movies += Movie(
                                id = "m$count", name = name, poster = logo, categoryId = cat.id, url = url,
                                year = Regex("\\((\\d{4})\\)").find(name)?.groupValues?.get(1) ?: "",
                                added = count.toLong(), extension = ext.ifBlank { "mp4" },
                            )
                        }
                        ContentType.SERIES -> {
                            val cat = seriesCats.getOrPut(grp) { Category("s${seriesCats.size}", grp, ContentType.SERIES) }
                            val showName = (se?.groupValues?.get(1)?.trim()?.trimEnd('-', ':', '.')?.trim()).orEmpty().ifBlank { name }
                            val key = "${cat.id}|${showName.lowercase()}"
                            val s = seriesMap.getOrPut(key) {
                                Series(id = "s${seriesMap.size}", name = showName, poster = logo, categoryId = cat.id, added = count.toLong())
                            }
                            episodes.getOrPut(s.id) { mutableListOf() } += Episode(
                                id = "e$count",
                                title = name,
                                season = se?.groupValues?.get(2)?.toIntOrNull() ?: 1,
                                episode = se?.groupValues?.get(3)?.toIntOrNull() ?: (episodes[s.id]?.size ?: 0) + 1,
                                image = logo,
                                url = url,
                            )
                        }
                    }
                    attrs = emptyMap(); title = ""; group = ""; pendingUa = ""
                }
            }
        }
        episodes.values.forEach { l -> l.sortWith(compareBy({ it.season }, { it.episode })) }
        return Result(
            Library(
                playlistId = playlistId,
                liveCategories = liveCats.values.toList(),
                channels = channels,
                movieCategories = movieCats.values.toList(),
                movies = movies.asReversed().toList(), // newest entries are usually appended last
                seriesCategories = seriesCats.values.toList(),
                series = seriesMap.values.toList().asReversed().toList(),
                m3uEpisodes = episodes,
                loadedAt = System.currentTimeMillis(),
            ),
            epgUrl,
        )
    }

    private fun parseAttrs(line: String): Map<String, String> {
        val map = HashMap<String, String>(8)
        attrRegex.findAll(line).forEach { map[it.groupValues[1].lowercase()] = it.groupValues[2] }
        return map
    }

    /** Title is the text after the last comma that is outside quotes. */
    private fun extinfTitle(line: String): String {
        var inQuote = false
        var lastComma = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) { lastComma = i; break }
        }
        return if (lastComma >= 0) line.substring(lastComma + 1).trim() else ""
    }
}
