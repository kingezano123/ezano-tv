package com.ezano.tv.data

import kotlin.math.min

/**
 * "Find an event" – searches the whole TV guide AND channel names for a match/show
 * (e.g. "Lakers vs Celtics", "man utd liverpool", "UFC 310", "f1 qualifying") and
 * groups every channel that is showing the same event together.
 */
object EventFinder {

    enum class Source { GUIDE, CHANNEL_NAME }
    enum class When { LIVE, UPCOMING, EARLIER }

    data class EventGroup(
        val title: String,
        val description: String,
        val start: Long,
        val stop: Long,
        val whenType: When,
        val source: Source,
        /** Channels carrying this event, best first (favorites, then highest quality). */
        val channels: List<Channel>,
        /** Programme per channel id (for catch-up URLs). */
        val programmes: Map<String, Programme>,
        val score: Float,
        val fullMatch: Boolean,
    ) {
        val key: String get() = "$title|$start"
    }

    data class Results(
        val query: String,
        val live: List<EventGroup> = emptyList(),
        val upcoming: List<EventGroup> = emptyList(),
        val earlier: List<EventGroup> = emptyList(),
        /** Channels from categories that match the query (e.g. "NBA" -> NBA League Pass category). */
        val related: List<Channel> = emptyList(),
        val guideAvailable: Boolean = true,
    ) {
        val isEmpty get() = live.isEmpty() && upcoming.isEmpty() && earlier.isEmpty() && related.isEmpty()
        val totalChannels get() = (live + upcoming).sumOf { it.channels.size }
    }

    // ---------------------------------------------------------------- aliases

    private val aliasGroups: List<List<String>> = listOf(
        // soccer
        listOf("manchester united", "man utd", "man united", "mufc"),
        listOf("manchester city", "man city", "mcfc"),
        listOf("tottenham", "spurs", "tottenham hotspur"),
        listOf("paris saint germain", "psg", "paris sg", "paris st germain"),
        listOf("barcelona", "barca", "fc barcelona"),
        listOf("real madrid", "rmcf"),
        listOf("atletico madrid", "atletico", "atleti"),
        listOf("inter milan", "internazionale", "inter"),
        listOf("ac milan", "milan"),
        listOf("juventus", "juve"),
        listOf("bayern munich", "bayern munchen", "bayern", "fc bayern"),
        listOf("borussia dortmund", "dortmund", "bvb"),
        listOf("wolverhampton", "wolves"),
        listOf("newcastle", "newcastle united", "nufc"),
        listOf("west ham", "west ham united"),
        listOf("nottingham forest", "nottm forest", "forest"),
        listOf("brighton", "brighton hove albion"),
        listOf("leverkusen", "bayer leverkusen"),
        listOf("premier league", "epl", "prem"),
        listOf("champions league", "ucl", "uefa champions league"),
        listOf("europa league", "uel"),
        listOf("la liga", "laliga"),
        listOf("serie a", "seriea"),
        listOf("world cup", "fifa world cup"),
        listOf("mls", "major league soccer"),
        // US sports
        listOf("los angeles lakers", "la lakers", "lakers"),
        listOf("los angeles clippers", "la clippers", "clippers"),
        listOf("boston celtics", "celtics"),
        listOf("new york knicks", "ny knicks", "knicks"),
        listOf("philadelphia 76ers", "76ers", "sixers"),
        listOf("golden state warriors", "golden state", "warriors", "gsw"),
        listOf("cleveland cavaliers", "cavaliers", "cavs"),
        listOf("dallas mavericks", "mavericks", "mavs"),
        listOf("portland trail blazers", "trail blazers", "blazers"),
        listOf("minnesota timberwolves", "timberwolves", "twolves"),
        listOf("oklahoma city thunder", "okc thunder", "okc"),
        listOf("san francisco 49ers", "49ers", "niners"),
        listOf("new england patriots", "patriots", "pats"),
        listOf("tampa bay buccaneers", "buccaneers", "bucs"),
        listOf("jacksonville jaguars", "jaguars", "jags"),
        listOf("kansas city chiefs", "chiefs"),
        listOf("dallas cowboys", "cowboys"),
        listOf("green bay packers", "packers"),
        listOf("new york yankees", "yankees", "nyy"),
        listOf("boston red sox", "red sox"),
        listOf("los angeles dodgers", "dodgers"),
        listOf("monday night football", "mnf"),
        listOf("sunday night football", "snf"),
        listOf("thursday night football", "tnf"),
        listOf("nfl", "national football league"),
        listOf("nba", "national basketball association"),
        listOf("nhl", "national hockey league"),
        listOf("mlb", "major league baseball"),
        listOf("ncaa", "college football", "college basketball", "ncaaf", "ncaab"),
        // motorsport / combat / other
        listOf("formula 1", "formula one", "f1", "grand prix"),
        listOf("motogp", "moto gp"),
        listOf("nascar", "nascar cup series"),
        listOf("ufc", "ultimate fighting championship"),
        listOf("wwe", "world wrestling entertainment"),
        listOf("raw", "monday night raw", "wwe raw"),
        listOf("smackdown", "friday night smackdown"),
        listOf("boxing", "fight night"),
        listOf("wimbledon", "the championships"),
        listOf("us open", "usopen"),
        listOf("super bowl", "superbowl"),
        listOf("olympics", "olympic games"),
        listOf("cricket", "ipl", "test match", "t20"),
    )

    private val aliasIndex: Map<String, List<String>> by lazy {
        val m = HashMap<String, List<String>>()
        aliasGroups.forEach { g -> g.forEach { v -> m[v] = g } }
        m
    }

    private val stopwords = setOf(
        "vs", "v", "versus", "at", "the", "live", "game", "match", "watch", "stream", "channel", "tonight", "today",
        "and", "fc", "cf", "sc", "afc", "on", "of", "a", "x", "contra", "gegen", "tv",
    )

    private val sideSplit = Regex("\\s+(?:vs\\.?|v\\.?|versus|at|@|x|-|contra|gegen)\\s+", RegexOption.IGNORE_CASE)

    /** A query side, e.g. "boston celtics". Each term is a set of alternative spellings. */
    private class Term(val alts: List<String>, val weight: Float)
    private class Side(val terms: List<Term>) {
        val totalWeight = terms.sumOf { it.weight.toDouble() }.toFloat()
    }

    private fun buildSides(query: String): List<Side> {
        val rawSides = query.split(sideSplit).map { Repo.normalize(it) }.filter { it.isNotBlank() }
        return rawSides.mapNotNull { side ->
            val terms = ArrayList<Term>()
            var rest = " $side "
            // Greedy alias phrase extraction (longest first)
            aliasIndex.keys.sortedByDescending { it.length }.forEach { phrase ->
                if (rest.contains(" $phrase ")) {
                    terms += Term(aliasIndex[phrase]!!, 2f)
                    rest = rest.replace(" $phrase ", " ")
                }
            }
            val hadAlias = terms.isNotEmpty()
            val words = rest.trim().split(' ').filter { it.isNotBlank() && it !in stopwords }
            words.forEachIndexed { i, w ->
                // Last word of a side (usually the nickname) counts double
                terms += Term(listOf(w), if (i == words.lastIndex && !hadAlias && words.size > 1) 2f else 1f)
            }
            if (terms.isEmpty()) null else Side(terms)
        }
    }

    // ---------------------------------------------------------------- matching

    /** Whole-word (or whole-phrase) match; long words may also match a plural/possessive suffix. */
    private fun containsWord(text: String, alt: String): Boolean {
        var idx = text.indexOf(alt)
        while (idx >= 0) {
            val before = idx == 0 || text[idx - 1] == ' '
            val endIdx = idx + alt.length
            val after = endIdx == text.length || text[endIdx] == ' ' ||
                (alt.length >= 5 && text[endIdx] == 's' && (endIdx + 1 == text.length || text[endIdx + 1] == ' '))
            if (before && after) return true
            idx = text.indexOf(alt, idx + 1)
        }
        return false
    }

    private fun fuzzyWord(text: String, alt: String): Boolean {
        if (alt.length < 5 || alt.contains(' ')) return false
        return text.split(' ').any { w -> kotlin.math.abs(w.length - alt.length) <= 1 && w.length >= 4 && editDistance(w, alt) <= 1 }
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun sideScore(side: Side, text: String, fuzzy: Boolean): Float {
        var got = 0f
        for (t in side.terms) {
            if (t.alts.any { containsWord(text, it) || (fuzzy && fuzzyWord(text, it)) }) got += t.weight
        }
        return if (side.totalWeight == 0f) 0f else got / side.totalWeight
    }

    /** Returns (score, fullMatch). */
    private fun score(sides: List<Side>, text: String, fuzzy: Boolean): Pair<Float, Boolean> {
        if (sides.size == 1) {
            val s = sideScore(sides[0], text, fuzzy)
            return s to (s >= 0.99f)
        }
        val scores = sides.map { sideScore(it, text, fuzzy) }
        val matched = scores.count { it >= 0.5f }
        return when {
            matched == sides.size -> (1f + scores.average().toFloat()) to true
            matched >= 1 -> (0.55f * scores.max()) to false
            else -> 0f to false
        }
    }

    // ---------------------------------------------------------------- index cache

    private class GuideIndex(
        val epg: EpgData,
        val entries: List<Pair<String, Programme>>, // epgKey -> programme
        val normTitles: Array<String>,
        val normDescs: Array<String>,
    )

    @Volatile private var cached: GuideIndex? = null

    private fun guideIndex(epg: EpgData): GuideIndex {
        cached?.let { if (it.epg === epg) return it }
        val entries = ArrayList<Pair<String, Programme>>(epg.programmeCount)
        epg.byKey.forEach { (k, list) -> list.forEach { entries += k to it } }
        val titles = Array(entries.size) { Repo.normalize(entries[it].second.title) }
        val descs = Array(entries.size) { Repo.normalize(entries[it].second.desc.take(160)) }
        return GuideIndex(epg, entries, titles, descs).also { cached = it }
    }

    /** Call when the guide finishes loading so the first search is instant. */
    fun warmUp(epg: EpgData) { if (!epg.isEmpty) guideIndex(epg) }

    // ---------------------------------------------------------------- search

    fun find(query: String, now: Long = System.currentTimeMillis()): Results {
        val idx = Repo.index.value ?: return Results(query)
        val epg = Repo.epg.value
        val sides = buildSides(query)
        if (sides.isEmpty()) return Results(query)

        val favorites = Repo.store.favoriteKeys().filter { it.startsWith("live:") }.mapTo(HashSet()) { it.removePrefix("live:") }
        val showAdult = Repo.settings.showAdult
        val okCats = Repo.visibleCategories(ContentType.LIVE).mapTo(HashSet()) { it.id }
        val channels = idx.lib.channels.filter { showAdult || it.categoryId in okCats || it.categoryId.isBlank() }

        // epg key -> channels sharing that guide entry (this is how we find *connecting* channels)
        val channelsByKey = HashMap<String, MutableList<Channel>>()
        if (!epg.isEmpty) channels.forEach { c -> epg.keyFor(c)?.let { channelsByKey.getOrPut(it) { mutableListOf() } += c } }

        val groups = LinkedHashMap<String, EventGroupBuilder>()

        fun collect(fuzzy: Boolean) {
            if (epg.isEmpty) return
            val gi = guideIndex(epg)
            for (i in gi.entries.indices) {
                val (key, p) = gi.entries[i]
                if (p.stop < now - 24 * 3600_000L) continue
                if (p.start > now + 3 * 24 * 3600_000L) continue
                val chs = channelsByKey[key] ?: continue
                val (ts, tFull) = score(sides, gi.normTitles[i], fuzzy)
                var s = ts; var full = tFull
                if (s < 0.5f) {
                    val (ds, dFull) = score(sides, gi.normTitles[i] + " " + gi.normDescs[i], fuzzy)
                    if (ds * 0.8f > s) { s = ds * 0.8f; full = dFull }
                }
                if (s < 0.5f) continue
                val w = when {
                    p.isLive(now) -> When.LIVE
                    p.start > now -> When.UPCOMING
                    else -> When.EARLIER
                }
                if (w == When.EARLIER && chs.none { it.catchupDays > 0 }) continue
                val gKey = Repo.normalize(p.title) + "|" + (p.start / 60_000L)
                val b = groups.getOrPut(gKey) { EventGroupBuilder(p.title, p.desc, p.start, p.stop, w, Source.GUIDE, s, full) }
                b.score = maxOf(b.score, s); b.full = b.full || full
                chs.forEach { c -> if (b.channels.add(c)) b.programmes[c.id] = p }
            }
        }
        collect(fuzzy = false)
        if (groups.size < 3) collect(fuzzy = true)

        // Event channels: many providers name PPV/event channels after the event itself,
        // e.g. "NBA 04: Lakers vs Celtics @ 7:30PM ET".
        channels.forEachIndexed { _, c ->
            val n = Repo.normalize(c.name)
            val (s, full) = score(sides, n, false)
            if (s >= 0.5f && (full || sides.size > 1)) {
                val gKey = "ch|" + Repo.normalize(stripChannelPrefix(c.name))
                val nowProg = epg.now(c, now)
                val b = groups.getOrPut(gKey) {
                    EventGroupBuilder(
                        title = nowProg?.title?.takeIf { it.isNotBlank() } ?: stripChannelPrefix(c.name),
                        desc = nowProg?.desc ?: "",
                        start = nowProg?.start ?: 0L, stop = nowProg?.stop ?: 0L,
                        whenType = When.LIVE, source = Source.CHANNEL_NAME, score = s * 0.95f, full = full,
                    )
                }
                if (b.channels.add(c) && nowProg != null) b.programmes[c.id] = nowProg
            }
        }

        fun rankChannels(list: Collection<Channel>): List<Channel> = list.sortedWith(
            compareByDescending<Channel> { it.id in favorites }.thenByDescending { quality(it.name) }.thenBy { it.number }
        )

        val built = groups.values.map { b ->
            EventGroup(b.title, b.desc, b.start, b.stop, b.whenType, b.source, rankChannels(b.channels), b.programmes, b.score, b.full)
        }
        val cmp = compareByDescending<EventGroup> { it.fullMatch }.thenByDescending { it.score }
        val live = built.filter { it.whenType == When.LIVE }.sortedWith(cmp.thenByDescending { it.channels.size })
        val upcoming = built.filter { it.whenType == When.UPCOMING }.sortedWith(compareByDescending<EventGroup> { it.fullMatch }.thenBy { it.start }).take(40)
        val earlier = built.filter { it.whenType == When.EARLIER }.sortedWith(compareByDescending<EventGroup> { it.fullMatch }.thenByDescending { it.start }).take(20)

        // Related channels from matching categories (e.g. "NBA", "Premier League")
        val inResults = built.flatMapTo(HashSet()) { g -> g.channels.map { it.id } }
        val catMatches = Repo.visibleCategories(ContentType.LIVE).filter { cat -> score(sides, Repo.normalize(cat.name), false).first >= 0.99f }
        val related = rankChannels(catMatches.flatMap { idx.channelsByCat[it.id] ?: emptyList() }.filter { it.id !in inResults }).take(40)

        return Results(query, live, upcoming, earlier, related, guideAvailable = !epg.isEmpty)
    }

    private class EventGroupBuilder(
        val title: String, val desc: String, val start: Long, val stop: Long,
        val whenType: When, val source: Source, var score: Float, var full: Boolean,
    ) {
        val channels = LinkedHashSet<Channel>()
        val programmes = HashMap<String, Programme>()
    }

    private fun stripChannelPrefix(name: String): String =
        name.replace(Regex("^[A-Za-z0-9+ ]{1,14}?\\s*\\d{0,3}\\s*[:|]\\s*"), "").trim().ifBlank { name }

    /** Prefer 4K > FHD > HD > SD, avoid backups. */
    fun quality(name: String): Int {
        val n = name.lowercase()
        var q = 0
        if (Regex("\\b(4k|uhd|2160p?)\\b").containsMatchIn(n)) q = 4
        else if (Regex("\\b(fhd|1080p?)\\b").containsMatchIn(n)) q = 3
        else if (Regex("\\b(hd|720p?)\\b").containsMatchIn(n)) q = 2
        else if (Regex("\\bsd\\b").containsMatchIn(n)) q = 0 else q = 1
        if ("backup" in n || "bkp" in n) q -= 2
        return q
    }
}
