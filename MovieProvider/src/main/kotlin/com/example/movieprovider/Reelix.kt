package com.example.movieprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class Reelix : MainAPI() {
    override var mainUrl = "https://reelix.ac"
    override var name = "Reelix"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private companion object {
        const val API_LANG = "en"

        const val SEARCH_FN = "cc48b63999128bfa4cbc3e71752e632ab74077abfacb80b9f3e42796a4711c28"
        const val HOME_FN = "ed9919c679069d34eb00fecca328293f64e92487b2c42daf3c54807626f1ab70"
        const val DETAILS_FN = "a6fff7597c9ac97a096d9579ecfc92f89ba40083970d0ef09d68b514f4097315"
        const val EPISODES_FN = "fcdfe48157410177238cf68ab5792d838c359a0b054bead22e24f99a5c3f66e9"
        const val RECS_FN = "cc8ece31eba14c95ac1289a8eae31f48cdbd31002a0acd4ff8de6c15a0aae461"
        const val LIST_FN = "ff28fae10dc7928d9028fd470b4ed4f031e0fb1fced20dea02abeafedfb4ed0f"
        const val PROVIDERS_FN = "788c0690e97bcd9a2b76b55e9d67d1aed390fbf8946c2ead35abe4f8b1464f55"

        const val MAX_ROW_ITEMS = 24

        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        const val SUB_SOURCE = "https://www.subtitlecat.com"
    }

    private val homeRows = listOf(
        "trending" to "Trending",
        "newReleases" to "New Releases",
        "popularMovies" to "Popular Movies",
        "popularTv" to "Popular TV",
        "popularAnime" to "Popular Anime",
        "topRated" to "Top Rated",
    )

    private val browseRows = listOf(
        "trending" to "Trending (All)",
        "movies" to "Movies (All)",
        "tv" to "TV Series (All)",
        "anime" to "Anime (All)",
    )

    // provider chips on the site (<button aria-label=...> /providers/*.svg)
    private val providerRows = listOf(
        "Netflix",
        "Disney+",
        "Crunchyroll",
        "Apple TV",
        "Prime Video",
        "Max",
        "Hulu",
    )

    private val autoPlayScript = """
        if (!window.__csPlay) {
            window.__csPlay = 1;
            setTimeout(function () {
                try {
                    var v = document.querySelector('video');
                    if (v && v.paused) { v.play(); }
                } catch (e) {}
            }, 1500);
        }
    """.trimIndent()

    // ---------------------------------------------------------------- main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null

        val lists = coroutineScope {
            val providerJobs = providerRows.map { name -> async { fetchProviderRow(name) } }

            val result = callServerFn(HOME_FN, dataObject(listOf("lang" to sStr(API_LANG)))) as? Map<*, *>

            val homeLists = result?.let { home ->
                homeRows.mapNotNull { (key, label) ->
                    val raw = home[key] as? List<*> ?: return@mapNotNull null
                    val items = raw.mapNotNull { itemToResponse(it as? Map<*, *>) }
                    if (items.isEmpty()) null else HomePageList(label, items, true)
                }
            }.orEmpty().toMutableList()

            if (homeLists.isNotEmpty()) {
                browseRows.forEach { (key, label) ->
                    fetchBrowseList(key, label)?.let { homeLists += it }
                }
            }

            homeLists + providerJobs.awaitAll().filterNotNull()
        }

        return if (lists.isEmpty()) null else newHomePageResponse(lists)
    }

    private suspend fun fetchProviderRow(name: String): HomePageList? {
        val fields = listOf(
            "name" to sStr(name),
            "lang" to sStr(API_LANG),
        )

        var items: List<SearchResponse> = emptyList()
        for (attempt in 1..3) {
            val result = try {
                callServerFn(PROVIDERS_FN, dataObject(fields)) as? List<*>
            } catch (_: Exception) {
                null
            }
            items = result.orEmpty().mapNotNull { itemToResponse(it as? Map<*, *>) }
            if (items.isNotEmpty()) break
            delay(700L * attempt)
        }
        if (items.isEmpty()) return null

        return HomePageList("Popular on $name", items.take(MAX_ROW_ITEMS), true)
    }

    private suspend fun fetchBrowseList(list: String, label: String): HomePageList? {
        val fields = listOf(
            "list" to sStr(list),
            "lang" to sStr(API_LANG),
        )
        val result = try {
            callServerFn(LIST_FN, dataObject(fields)) as? Map<*, *> ?: return null
        } catch (_: Exception) {
            return null
        }
        val items = (result["items"] as? List<*>)?.mapNotNull { itemToResponse(it as? Map<*, *>) }
            .orEmpty()
        if (items.isEmpty()) return null
        return HomePageList(label, items, true)
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val fields = listOf(
            "query" to sStr(trimmed),
            "lang" to sStr(API_LANG),
        )
        val result = callServerFn(SEARCH_FN, dataObject(fields)) as? List<*> ?: return emptyList()

        return result.mapNotNull { entry ->
            val row = entry as? Map<*, *> ?: return@mapNotNull null
            if (row["kind"] != "title") return@mapNotNull null
            itemToResponse(row["item"] as? Map<*, *>)
        }
    }

    // ---------------------------------------------------------------- details

    override suspend fun load(url: String): LoadResponse? {
        val info = try {
            JSONObject(url)
        } catch (_: Exception) {
            throw ErrorLoadingException("Reelix: unsupported link")
        }

        val id = info.optString("id")
        if (id.isBlank()) throw ErrorLoadingException("Reelix: missing id")

        val listedTitle = info.optString("t")
        val isTv = info.optString("k").equals("TV", true)
        val poster = info.optString("p").ifBlank { null }
        val listedYear = (info.opt("y") as? Number)?.toInt()
        val tmdbId = id.substringAfter('-', "")
        if (tmdbId.isBlank()) throw ErrorLoadingException("Reelix: bad id")

        val fields = listOf(
            "title" to sStr(listedTitle),
            "type" to sStr(if (isTv) "TV" else "Movie"),
            "lang" to sStr(API_LANG),
            "id" to sStr(id),
        )
        val details = callServerFn(DETAILS_FN, dataObject(fields)) as? Map<*, *>
            ?: throw ErrorLoadingException("Reelix: no details")

        val title = listOf(details["name"], listedTitle)
            .filterIsInstance<String>()
            .firstOrNull { it.isNotBlank() }
            ?: throw ErrorLoadingException("Reelix: no title")

        val plot = (details["synopsis"] as? String)?.ifBlank { null }
        val year = (details["year"] as? Number)?.toInt() ?: listedYear
        val rating = (details["rating"] as? Number)?.toDouble()?.takeIf { it > 0.0 }
        val score = rating?.let { Score.from10(it) }
        val tags = (details["genres"] as? List<*>)
            ?.mapNotNull { (it as? String)?.ifBlank { null } }
            ?.ifEmpty { null }
        val backdrop = (details["backdrop"] as? String)?.ifBlank { null }
        val contentRating = (details["ageRating"] as? String)?.ifBlank { null }
        val duration = (details["runtime"] as? String)?.let { parseRuntime(it) }
        val recommendations = fetchRecommendations(id).ifEmpty { null }
        val trailers = (details["trailer"] as? String)?.ifBlank { null }?.let { trailerId ->
            mutableListOf(TrailerData(
                extractorUrl = "https://www.youtube.com/watch?v=$trailerId",
                referer = "$mainUrl/",
                raw = false,
                headers = emptyMap(),
            ))
        } ?: mutableListOf()

        if (!isTv) {
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                encodeStreamData(vidcoreMovie(tmdbId), title, year, null, null),
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = score
                this.duration = duration
                this.contentRating = contentRating
                this.backgroundPosterUrl = backdrop
                this.recommendations = recommendations
                this.trailers = trailers
            }
        }

        val seasonEntries = (details["seasons"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.filter { (it["number"] as? Number) != null } ?: emptyList()

        val seasonNumbers = seasonEntries
            .mapNotNull { (it["number"] as? Number)?.toInt() }
            .distinct()
            .sorted()
            .ifEmpty { listOf(1) }

        val seasonNames = seasonEntries.mapNotNull { entry ->
            val number = (entry["number"] as? Number)?.toInt() ?: return@mapNotNull null
            SeasonData(number, entry["name"] as? String, null)
        }.ifEmpty { null }

        val episodes = mutableListOf<Episode>()
        for (season in seasonNumbers) {
            episodes += fetchEpisodes(id, tmdbId, season, title)
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("Reelix: no episodes")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.score = score
            this.duration = duration
            this.contentRating = contentRating
            this.backgroundPosterUrl = backdrop
            this.trailers = trailers
            this.recommendations = recommendations
            if (seasonNames != null) this.seasonNames = seasonNames
        }
    }

    private suspend fun fetchEpisodes(
        id: String,
        tmdbId: String,
        season: Int,
        showTitle: String,
    ): List<Episode> {
        val fields = listOf(
            "id" to sStr(id),
            "season" to sInt(season),
            "lang" to sStr(API_LANG),
        )
        val raw = try {
            callServerFn(EPISODES_FN, dataObject(fields)) as? List<*> ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }

        return raw.mapNotNull { entry ->
            val episode = entry as? Map<*, *> ?: return@mapNotNull null
            val number = (episode["number"] as? Number)?.toInt() ?: return@mapNotNull null
            newEpisode(
                encodeStreamData(
                    vidcoreTv(tmdbId, season, number),
                    showTitle,
                    null,
                    season,
                    number,
                )
            ) {
                this.season = season
                this.episode = number
                this.name = episode["title"] as? String
                this.posterUrl = episode["still"] as? String
                this.description = episode["synopsis"] as? String
                this.runTime = (episode["runtime"] as? String)?.let { parseRuntime(it) }
            }
        }.sortedBy { it.episode ?: 0 }
    }

    private suspend fun fetchRecommendations(id: String): List<SearchResponse> {
        val fields = listOf(
            "id" to sStr(id),
            "lang" to sStr(API_LANG),
        )
        val raw = try {
            callServerFn(RECS_FN, dataObject(fields)) as? List<*> ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        return raw.mapNotNull { itemToResponse(it as? Map<*, *>) }
    }

    // ---------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val stream = decodeStreamData(data) ?: return false

        for (target in streamTargets(stream.url)) {
            val link = resolveStream(target) ?: continue
            callback(link)
            findArabicSubtitle(stream)?.let { subtitleCallback(it) }
            return true
        }
        return false
    }

    private data class StreamData(
        val url: String,
        val title: String?,
        val year: Int?,
        val season: Int?,
        val episode: Int?,
    )

    private fun encodeStreamData(
        url: String,
        title: String?,
        year: Int?,
        season: Int?,
        episode: Int?,
    ): String {
        val json = JSONObject()
        json.put("u", url)
        if (!title.isNullOrBlank()) json.put("t", title)
        if (year != null) json.put("y", year)
        if (season != null) json.put("s", season)
        if (episode != null) json.put("e", episode)
        return json.toString()
    }

    private fun decodeStreamData(data: String): StreamData? {
        val json = try {
            JSONObject(data)
        } catch (_: Exception) {
            null
        }
        if (json == null) {
            return if (data.startsWith("http")) StreamData(data, null, null, null, null) else null
        }
        val url = json.optString("u").ifBlank { null } ?: return null
        if (!url.startsWith("http")) return null
        return StreamData(
            url,
            json.optString("t").ifBlank { null },
            json.optInt("y", 0).takeIf { it > 0 },
            json.optInt("s", 0).takeIf { it > 0 },
            json.optInt("e", 0).takeIf { it > 0 },
        )
    }

    private suspend fun findArabicSubtitle(stream: StreamData): SubtitleFile? {
        val title = stream.title ?: return null
        val query = if (stream.season != null && stream.episode != null) {
            "$title S" + stream.season.toString().padStart(2, '0') +
                "E" + stream.episode.toString().padStart(2, '0')
        } else {
            listOfNotNull(title, stream.year?.toString()).joinToString(" ")
        }

        val headers = mapOf(
            "User-Agent" to BROWSER_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "$mainUrl/",
        )

        val search = try {
            app.get(
                "$SUB_SOURCE/index.php?search=" + URLEncoder.encode(query, "UTF-8"),
                headers = headers,
            ).text
        } catch (_: Exception) {
            return null
        }

        val pages = Regex("""href="(subs/[^"]+\.html)"""")
            .findAll(search)
            .map { it.groupValues[1] }
            .take(3)
            .toList()

        for (page in pages) {
            val html = try {
                app.get("$SUB_SOURCE/$page", headers = headers).text
            } catch (_: Exception) {
                continue
            }
            val match = Regex("""id="download_ar"[^>]*href="([^"]+)"""").find(html) ?: continue
            val href = match.groupValues[1]
            val absolute = when {
                href.startsWith("http") -> href
                href.startsWith("/") -> SUB_SOURCE + href
                else -> "$SUB_SOURCE/$href"
            }
            return SubtitleFile("Arabic", absolute.replace(" ", "%20")).apply {
                this.headers = mapOf("User-Agent" to BROWSER_UA)
            }
        }
        return null
    }

    private fun streamTargets(primary: String): List<String> {
        val targets = mutableListOf(primary)
        val match = Regex("""^https://vidcore\.io/(movie|tv)/([^?]+)""").find(primary) ?: return targets
        val kind = match.groupValues[1]
        val rest = match.groupValues[2]
        if (kind == "movie") {
            targets += "https://vsembed.su/embed/movie/$rest"
            targets += "https://www.vidy.st/movie/$rest?autoplay=1"
            targets += "https://vidbolt.xyz/embed/movie/$rest"
        } else {
            targets += "https://vsembed.su/embed/tv/$rest"
            targets += "https://www.vidy.st/tv/$rest?autoplay=1"
            targets += "https://vidbolt.xyz/embed/tv/$rest"
        }
        return targets
    }

    private suspend fun resolveStream(target: String): ExtractorLink? {
        val primary = target.startsWith("https://vidcore.io")
        val resolver = WebViewResolver(
            interceptUrl = Regex("""\.(?:m3u8|mp4)(?:\?|$)""", RegexOption.IGNORE_CASE),
            additionalUrls = emptyList(),
            userAgent = null,
            useOkhttp = false,
            script = autoPlayScript,
            scriptCallback = null,
            timeout = if (primary) 40_000L else 25_000L,
        )

        val resolved = try {
            resolver.resolveUsingWebView(target, "$mainUrl/", "GET") { true }
        } catch (_: Exception) {
            null
        }
        val request = resolved?.first ?: return null

        val streamUrl = request.url.toString()
        if (streamUrl.isBlank()) return null

        val referer = if (primary) {
            "https://vidcore.io/"
        } else {
            request.headers["Referer"]?.ifBlank { null } ?: pageOrigin(target)
        }

        return newExtractorLink(
            source = name,
            name = name,
            url = streamUrl,
            type = if (streamUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.referer = referer
            this.headers = mapOf(
                "Referer" to referer,
                "User-Agent" to BROWSER_UA,
            )
        }
    }

    private fun pageOrigin(url: String): String {
        val base = Regex("""^(https?://[^/?#]+)""").find(url)?.groupValues?.get(1)
            ?: return "$mainUrl/"
        return "$base/"
    }

    // ---------------------------------------------------------------- players

    private fun vidcoreMovie(tmdbId: String): String =
        "https://vidcore.io/movie/$tmdbId?theme=d7dee2&autoPlay=true&autoNext=true" +
            "&poster=true&title=true&chromecast=true&sub=en"

    private fun vidcoreTv(tmdbId: String, season: Int, episode: Int): String =
        "https://vidcore.io/tv/$tmdbId/$season/$episode?theme=d7dee2&autoPlay=true&autoNext=true" +
            "&nextButton=true&poster=true&title=true&chromecast=true&sub=en"

    private fun parseRuntime(text: String): Int? {
        val hours = Regex("""(\d+)\s*h""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d+)\s*m""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = hours * 60 + minutes
        if (total > 0) return total
        return text.trim().toIntOrNull()
    }

    private fun itemToResponse(item: Map<*, *>?): SearchResponse? {
        if (item == null) return null

        val id = item["id"] as? String ?: return null
        val title = (item["title"] as? String)?.ifBlank { null } ?: return null
        val kind = item["type"] as? String ?: return null
        val poster = (item["poster"] as? String)?.ifBlank { null }
        val year = (item["year"] as? Number)?.toInt()
        val rating = (item["rating"] as? Number)?.toDouble()?.takeIf { it > 0.0 }
        val score = rating?.let { Score.from10(it) }
        val url = encodeInfo(id, title, kind, poster, year)

        return if (kind.equals("TV", true)) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        }
    }

    private fun encodeInfo(id: String, title: String, kind: String, poster: String?, year: Int?): String {
        val json = JSONObject()
        json.put("id", id)
        json.put("t", title)
        json.put("k", kind)
        if (poster != null) json.put("p", poster)
        if (year != null) json.put("y", year)
        return json.toString()
    }

    // ---------------------------------------------------------------- seroval

    private fun serverFnHeaders(): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "application/json",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "x-tsr-serverFn" to "true",
        "sec-fetch-dest" to "empty",
        "sec-fetch-mode" to "cors",
        "sec-fetch-site" to "same-origin",
    )

    private fun sStr(value: String): String = """{"t":1,"s":${JSONObject.quote(value)}}"""

    private fun sInt(value: Int): String = """{"t":0,"s":$value}"""

    private fun dataObject(fields: List<Pair<String, String>>): String {
        val keys = fields.joinToString(",") { JSONObject.quote(it.first) }
        val values = fields.joinToString(",") { it.second }
        return """{"t":10,"i":1,"p":{"k":[$keys],"v":[$values],"s":${fields.size}},"o":0}"""
    }

    private suspend fun callServerFn(hash: String, data: String): Any? {
        val payload =
            """{"t":{"t":10,"i":0,"p":{"k":["data"],"v":[$data],"s":1},"o":0},"f":31,"m":[]}"""
        val encoded = URLEncoder.encode(payload, "UTF-8").replace("+", "%20")
        val text = app.get("$mainUrl/_serverFn/$hash?payload=$encoded", headers = serverFnHeaders()).text
        return unwrapServerFn(text)
    }

    private fun unwrapServerFn(text: String): Any? {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw ErrorLoadingException("Reelix: invalid API response")
        }
        if (!root.has("t")) throw ErrorLoadingException("Reelix: unexpected API response")

        val refs = HashMap<Int, Any?>()
        val decoded = decodeNode(root, refs)
        if (decoded !is Map<*, *>) throw ErrorLoadingException("Reelix: unexpected API response")
        if (decoded["error"] != null) throw ErrorLoadingException("Reelix: API error")
        return decoded["result"]
    }

    private fun decodeNode(node: JSONObject, refs: HashMap<Int, Any?>): Any? {
        val index = node.optInt("i", -1)
        return when (val type = node.optInt("t", -1)) {
            0 -> node.opt("s") as? Number
            1 -> node.optString("s", "")
            2 -> when (node.optInt("s", -1)) {
                0, 1 -> null
                2 -> true
                3 -> false
                4 -> -0.0
                5 -> Double.POSITIVE_INFINITY
                6 -> Double.NEGATIVE_INFINITY
                7 -> Double.NaN
                else -> null
            }
            3 -> node.optString("s", "")
            4 -> refs[index]
            5, 6 -> node.opt("s")?.toString()
            7 -> {
                val set = LinkedHashSet<Any?>()
                if (index >= 0) refs[index] = set
                fillInto(node.optJSONArray("a"), set, refs)
                set
            }
            9 -> {
                val list = ArrayList<Any?>()
                if (index >= 0) refs[index] = list
                fillInto(node.optJSONArray("a"), list, refs)
                list
            }
            10, 11 -> {
                val map = LinkedHashMap<String, Any?>()
                if (index >= 0) refs[index] = map
                val payload = node.optJSONObject("p")
                val keys = payload?.optJSONArray("k")
                val values = payload?.optJSONArray("v")
                if (keys != null && values != null) {
                    for (i in 0 until keys.length()) {
                        val key = keys.optString(i)
                        map[key] = values.optJSONObject(i)?.let { decodeNode(it, refs) }
                    }
                }
                map
            }
            25 -> {
                val map = LinkedHashMap<String, Any?>()
                val payload = node.optJSONObject("s") ?: return map
                val keys = payload.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = payload.optJSONObject(key)?.let { decodeNode(it, refs) }
                }
                map
            }
            else -> node.opt("s")
        }
    }

    private fun fillInto(source: JSONArray?, target: MutableCollection<Any?>, refs: HashMap<Int, Any?>) {
        if (source == null) return
        for (i in 0 until source.length()) {
            target.add(source.optJSONObject(i)?.let { decodeNode(it, refs) })
        }
    }
}
