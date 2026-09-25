package com.example.movieprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MovieHd4k : MainAPI() {
    override var mainUrl = "https://moviehd-4k.com"
    override var name = "MovieHD 4K"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val homeSections = listOf(
        "Latest Movies" to "/latest-movie/",
        "Popular Movies" to "/popular-movie/",
        "Top Rated Movies" to "/top-rated-movie/",
        "Upcoming Movies" to "/upcoming-movie/",
        "Popular TV" to "/popular-tv/",
        "On Air TV" to "/on-the-air-tv/",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val lists = mutableListOf<HomePageList>()

        homeSections.forEach { (title, path) ->
            try {
                val items = parseCards(app.get(mainUrl + path).document)
                if (items.isNotEmpty()) lists.add(HomePageList(title, items, true))
            } catch (_: Exception) {
            }
        }

        return if (lists.isEmpty()) null else newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
            .joinToString("+") { it.encodeURL() }
        if (q.isBlank()) return emptyList()
        val doc = app.get("$mainUrl/search/$q.html").document
        return parseCards(doc)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("No title found")

        val poster = doc.selectFirst("a.mvi-cover")?.attr("style")
            ?.let { Regex("""url\('([^']+)'\)""").find(it)?.groupValues?.get(1) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }

        val plot = doc.selectFirst(".mvic-desc .desc, .desc")?.text()?.trim()?.ifBlank { null }

        val year = doc.selectFirst("a[href*=/year/]")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(doc.title() ?: "")?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""/year/(\d{4})""").find(doc.toString())?.groupValues?.get(1)?.toIntOrNull()

        val tags = doc.select(".mvici-left p").firstOrNull { it.text().startsWith("Genre") }
            ?.select("a")?.map { it.text().trim() }?.distinct()?.ifEmpty { null }

        val score = Regex("""IMDb:?\s*(\d{1,2}\.\d{1,2})""").find(doc.toString())
            ?.groupValues?.get(1)?.toDoubleOrNull()?.let { Score.from10(it) }

        return if (Regex("""/tv/\d+/""").containsMatchIn(url)) {
            val episodes = parseSeasons(doc, url)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = score
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, buildWatchUrl(url)) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = score
            }
        }
    }

    private fun buildWatchUrl(detailUrl: String): String {
        if (detailUrl.endsWith("watchere.html")) return detailUrl
        return detailUrl.trimEnd('/') + "/watchere.html"
    }

    private fun absolute(href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("/") -> mainUrl + href
        else -> "$mainUrl/$href"
    }

    private suspend fun parseSeasons(doc: Document, showUrl: String): List<Episode> {
        val showId = Regex("""/tv/(\d+)/""").find(showUrl)?.groupValues?.get(1)
            ?: return emptyList()
        val seasonRx = Regex("""/tv/""" + Regex.escape(showId) + """-(\d+)/""")

        val seasonLinks = LinkedHashMap<Int, String>()
        doc.select("a[href]").forEach { a ->
            val m = seasonRx.find(a.attr("href")) ?: return@forEach
            val season = m.groupValues[1].toIntOrNull() ?: return@forEach
            if (!seasonLinks.containsKey(season)) seasonLinks[season] = a.attr("href")
        }

        val episodes = mutableListOf<Episode>()
        seasonLinks.toSortedMap().forEach { (season, href) ->
            try {
                val seasonDoc = app.get(absolute(href)).document
                val epRx = Regex("""/tv/""" + Regex.escape(showId) + """-""" + season + """-(\d+)/""")
                seasonDoc.select("a[href]").forEach { a ->
                    val m = epRx.find(a.attr("href")) ?: return@forEach
                    val epNum = m.groupValues[1].toIntOrNull() ?: return@forEach
                    val epTitle = a.attr("title").ifBlank { a.selectFirst("h2, h3")?.text() ?: "" }
                        .trim().ifBlank { "Episode $epNum" }
                    episodes.add(
                        newEpisode(absolute(a.attr("href"))) {
                            this.season = season
                            this.episode = epNum
                            this.name = epTitle
                        }
                    )
                }
            } catch (_: Exception) {
            }
        }

        return episodes.distinctBy { it.data }
    }

    private fun parseCards(doc: Document): List<SearchResponse> = parseCards(doc.select("div.ml-item"))

    private fun parseCards(elements: List<Element>): List<SearchResponse> = elements.mapNotNull { item ->
        val a = item.selectFirst("a[href]") ?: return@mapNotNull null
        val href = a.attr("href")
        if (href.isBlank() || !href.contains("/movie/") && !href.contains("/tv/")) return@mapNotNull null
        val url = absolute(href)

        var title = a.attr("title").ifBlank { item.selectFirst("h2, h3")?.text() ?: "" }.trim()
        if (title.isBlank()) return@mapNotNull null

        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
        title = title.replace(Regex("""\s*\((19|20)\d{2}\)\s*$"""), "").trim()
        if (title.isBlank()) return@mapNotNull null

        val poster = pickPoster(item)

        val score = item.selectFirst(".jt-imdb")?.text()
            ?.replace(Regex("""[^0-9.]"""), "")?.toDoubleOrNull()
            ?.let { Score.from10(it) }

        if (url.contains("/tv/")) {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = buildWatchUrl(data)
        val doc = try {
            app.get(watchUrl).document
        } catch (_: Exception) {
            return false
        }

        var found = false
        val seen = mutableSetOf<String>()

        doc.select("video source[src]").forEach { source ->
            val src = source.attr("src")
            if (src.isBlank() || !seen.add(src)) return@forEach
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = src,
                    type = if (src.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
            found = true
        }

        if (!found) {
            val text = doc.toString().replace("\\/", "/").replace("&amp;", "&")
            Regex("""https?://[^\s"'<>\\]+\.(?:mp4|m3u8)(?:[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
                .findAll(text)
                .map { it.value.trimEnd('.', ',', ';', ')', ']') }
                .distinct()
                .forEach { url ->
                    if (seen.add(url)) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = url,
                                type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                        )
                        found = true
                    }
                }
        }

        return found
    }

    private fun pickPoster(item: Element): String? {
        val img = item.selectFirst("img") ?: return null
        for (attr in listOf("data-original", "data-src", "src")) {
            val value: String = img.attr(attr)
            if (value.isNotBlank() && !value.startsWith("data:")) return value
        }
        return null
    }

    private fun String.encodeURL(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
