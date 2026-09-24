package com.example.movieprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class TopCinema : MainAPI() {
    override var mainUrl = "https://web5.topcinema.fan"
    override var name = "TopCinema"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "ar"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val ajaxBase = "$mainUrl/wp-content/themes/movies2023/Ajaxat/"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val doc = app.get(mainUrl).document
        val lists = mutableListOf<HomePageList>()

        val sliderItems = parseSmallBoxes(doc.select(".Slides--Item .Block--Item > a"))
        if (sliderItems.isNotEmpty()) {
            lists.add(HomePageList("الأكثر مشاهدة", sliderItems, true))
        }

        doc.select("section.Two--Items, section, div.Content--Wrapper").forEach { section ->
            val title = section.selectFirst(".Title--Box h3, .TitleBox h2")?.text()?.trim()
                ?: return@forEach
            if (title.isBlank()) return@forEach

            val items = parseSmallBoxes(section.select(".Small--Box > a.recent--block, .Small--Box > a"))
            val asideItems = parseAsidePosts(section.select(".AsidePost > a"))
            val combined = (items + asideItems).distinctBy { it.url }
            if (combined.isNotEmpty()) {
                lists.add(HomePageList(title, combined, true))
            }
        }

        if (lists.isEmpty()) {
            val recent = app.get("$mainUrl/recent/").document
            val items = parseSmallBoxes(recent.select(".Small--Box > a.recent--block"))
            if (items.isNotEmpty()) lists.add(HomePageList("المضاف حديثا", items, true))
        }

        return if (lists.isEmpty()) null else newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?query=${query.encodeURL()}&type=all"
        val doc = app.get(url).document
        return parseSmallBoxes(
            doc.select(".Posts--List .Small--Box > a.recent--block, .Posts--List .Small--Box > a")
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.post-title, h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("No title found")

        val poster = extractPoster(doc)
        val imdb = doc.selectFirst(".UnderPoster .imdbR span, .imdbR span")?.text()?.toDoubleOrNull()
        val plot = doc.selectFirst(".story p, .story")?.text()?.trim()

        val tags = doc.select(".RightTaxContent li").mapNotNull { li ->
            val label = li.selectFirst("span")?.text() ?: return@mapNotNull null
            if (label.contains("نوع") || label.contains("قسم") || label.contains("تصنيف")) {
                li.select("a").map { it.text().trim() }
            } else null
        }.flatten().distinct().ifEmpty { null }

        val year = doc.selectFirst(".RightTaxContent li a[href*=release-year]")?.text()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()

        val quality = doc.selectFirst(".ribbon")?.text()
            ?: doc.select(".RightTaxContent li")
                .firstOrNull { it.text().contains("جودة") }
                ?.select("a")?.firstOrNull()?.text()

        val score = imdb?.let { Score.from10(it) }

        val watchUrl = doc.selectFirst("a.watch, .BTNSDownWatch a.watch")?.attr("href")
            ?: if (url.endsWith("/watch/")) url else url.trimEnd('/') + "/watch/"

        val type = detectType(title, url)

        return when (type) {
            TvType.Movie -> newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = score
            }
            TvType.TvSeries -> {
                val episodes = parseEpisodes(doc)
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.score = score
                }
            }
            TvType.Anime -> {
                val episodes = parseEpisodes(doc)
                newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.score = score
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            }
            else -> null
        }
    }

    private fun extractPoster(doc: Document): String? {
        val img = doc.selectFirst(".MainSingle .left .image img, .left .image img")
        if (img != null) {
            val src = img.attr("src")
            val dataSrc = img.attr("data-src")
            val chosen = if (src.isNotBlank() && !src.endsWith("cover.jpg")) src
            else if (dataSrc.isNotBlank() && !dataSrc.endsWith("cover.jpg")) dataSrc
            else ""
            if (chosen.isNotBlank()) return chosen
        }
        return doc.selectFirst("meta[property=og:image]")?.attr("content")
    }

    private fun parseEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()

        doc.select("section.allepcont a[href]").forEach { a ->
            val href = a.attr("href")
            if (href.isBlank()) return@forEach
            val epTitle = a.selectFirst(".ep-info h2, .ep-info")?.text()?.trim()
                ?: a.attr("title")
            if (epTitle.isBlank()) return@forEach

            val epNumText = a.selectFirst(".epnum")?.text() ?: ""
            val epNum = epNumText.replace(Regex("[^0-9]"), "").toIntOrNull()
                ?: Regex("""الحلقة\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            val seasonWord = Regex("""الموسم\s*([^\s]+)""").find(epTitle)?.groupValues?.get(1)
            val season = seasonWord?.let { arabicSeasonToNumber(it) } ?: 1

            episodes.add(
                newEpisode(href) {
                    this.name = epTitle
                    this.season = season
                    this.episode = epNum
                }
            )
        }

        if (episodes.isEmpty()) {
            doc.select("section.allseasonss a[href], .Small--Box.Season a[href]").forEach { a ->
                val href = a.attr("href")
                if (href.isBlank()) return@forEach
                val sTitle = a.selectFirst("h3, .title")?.text()?.trim() ?: a.attr("title")
                val sNum = a.selectFirst(".epnum")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                    ?: Regex("""الموسم\s*(\d+)""").find(sTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1
                episodes.add(
                    newEpisode(href) {
                        this.name = sTitle
                        this.season = sNum
                        this.episode = 1
                    }
                )
            }
        }

        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = if (data.endsWith("/watch/") || data.endsWith("/watch")) data
        else data.trimEnd('/') + "/watch/"

        val doc = app.get(watchUrl).document
        var found = false

        val serverItems = doc.select(".watch--servers--list li.server--item")
        val postId = serverItems.firstOrNull()?.attr("data-id")

        val initialIframe = doc.selectFirst(".player--iframe iframe")?.attr("src")
        if (!initialIframe.isNullOrBlank()) {
            found = resolveEmbed(initialIframe, watchUrl, subtitleCallback, callback) || found
        }

        if (!postId.isNullOrBlank()) {
            serverItems.forEach { li ->
                val idx = li.attr("data-server")
                if (idx.isBlank()) return@forEach
                val serverName = li.selectFirst("span")?.text()?.trim() ?: "Server $idx"

                try {
                    val resp = app.post(
                        "${ajaxBase}Single/Server.php",
                        data = mapOf("id" to postId, "i" to idx),
                        headers = mapOf(
                            "Referer" to watchUrl,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to mainUrl,
                        )
                    ).text

                    val iframe = Regex("""src\s*=\s*["']([^"']+)["']""").find(resp)?.groupValues?.get(1)
                    if (!iframe.isNullOrBlank()) {
                        val ok = resolveEmbed(iframe, watchUrl, subtitleCallback, callback, serverName)
                        found = ok || found
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (!found) {
            try {
                val dlUrl = watchUrl.removeSuffix("/watch/") + "/download/"
                val dlDoc = app.get(dlUrl).document
                dlDoc.select("a[href]").forEach { a ->
                    val href = a.attr("href")
                    if (href.contains(".mp4") || href.contains(".m3u8")) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "Download",
                                url = href,
                                type = if (href.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                        )
                        found = true
                    }
                }
            } catch (_: Exception) {
            }
        }

        return found
    }

    private suspend fun resolveEmbed(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        sourceName: String = name
    ): Boolean {
        if (embedUrl.contains(".m3u8")) {
            callback(
                newExtractorLink(
                    source = sourceName,
                    name = sourceName,
                    url = embedUrl,
                    type = ExtractorLinkType.M3U8
                )
            )
            return true
        }
        if (embedUrl.contains(".mp4")) {
            callback(
                newExtractorLink(
                    source = sourceName,
                    name = sourceName,
                    url = embedUrl,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }

        return try {
            loadExtractor(embedUrl, referer, subtitleCallback, callback)
        } catch (_: Exception) {
            false
        }
    }

    private fun parseSmallBoxes(elements: List<Element>): List<SearchResponse> {
        return elements.mapNotNull { a ->
            val href = a.attr("href")
            if (href.isBlank()) return@mapNotNull null

            var title = a.attr("title")
            if (title.isBlank()) title = a.selectFirst("h3.title, h3")?.text() ?: ""
            title = title.trim()
            if (title.isBlank()) return@mapNotNull null

            val poster = extractCardPoster(a)
            val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            val imdbDouble = a.selectFirst(".imdbRating")?.text()
                ?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()
            val score = imdbDouble?.let { Score.from10(it) }

            val type = detectType(title, href)

            when (type) {
                TvType.Movie -> newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = score
                }
                TvType.TvSeries -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = score
                }
                TvType.Anime -> newAnimeSearchResponse(title, href) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = score
                }
                else -> newMovieSearchResponse(title, href, type) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = score
                }
            }
        }
    }

    private fun parseAsidePosts(elements: List<Element>): List<SearchResponse> {
        return elements.mapNotNull { a ->
            val href = a.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val title = a.selectFirst("h3")?.text()?.trim() ?: ""
            if (title.isBlank()) return@mapNotNull null

            val poster = extractCardPoster(a)
            val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            val type = detectType(title, href)

            when (type) {
                TvType.Movie -> newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
                TvType.TvSeries -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
                TvType.Anime -> newAnimeSearchResponse(title, href) {
                    this.posterUrl = poster
                    this.year = year
                }
                else -> newMovieSearchResponse(title, href, type) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        }
    }

    private fun extractCardPoster(a: Element): String? {
        val img = a.selectFirst("img") ?: return null
        val dataSrc = img.attr("data-src")
        val src = img.attr("src")
        val chosen = if (dataSrc.isNotBlank() && !dataSrc.endsWith("cover.jpg")) dataSrc
        else if (src.isNotBlank() && !src.endsWith("cover.jpg")) src
        else ""
        return chosen.ifBlank { null }
    }

    private fun detectType(title: String, url: String): TvType {
        val t = title.trim()
        return when {
            t.startsWith("فيلم") || url.contains("/film-") -> TvType.Movie
            t.startsWith("مسلسل") -> TvType.TvSeries
            t.startsWith("انمي") -> TvType.Anime
            url.contains("/series/") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun arabicSeasonToNumber(word: String): Int? {
        val map = mapOf(
            "الاول" to 1, "الأول" to 1, "الثاني" to 2, "الثانيه" to 2, "الثانية" to 2,
            "الثالث" to 3, "الثالثه" to 3, "الثالثة" to 3, "الرابع" to 4, "الخامس" to 5,
            "السادس" to 6, "السابع" to 7, "الثامن" to 8, "التاسع" to 9, "العاشر" to 10
        )
        return map[word.trim()] ?: word.toIntOrNull()
    }

    private fun String.encodeURL(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
