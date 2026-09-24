package com.example.movieprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MovieProvider : MainAPI() {
    override var mainUrl = "https://example-movies.com"
    override var name = "MovieProvider"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document

        val movies = document.select("div.movie-item").mapNotNull { element ->
            val title = element.select("h3").text().ifBlank { return@mapNotNull null }
            val url = element.selectFirst("a")?.attr("href")?.ifBlank { null }
                ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src")?.ifBlank { null }
            val year = element.selectFirst(".year")?.text()?.toIntOrNull()

            newMovieSearchResponse(title, fixUrl(url), TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
                this.year = year
            }
        }

        return newHomePageResponse(request.name, movies)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query").document

        return document.select("div.movie-item").mapNotNull { element ->
            val title = element.select("h3").text().ifBlank { return@mapNotNull null }
            val url = element.selectFirst("a")?.attr("href")?.ifBlank { null }
                ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src")?.ifBlank { null }
            val year = element.selectFirst(".year")?.text()?.toIntOrNull()

            newMovieSearchResponse(title, fixUrl(url), TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
                this.year = year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.ifBlank { null }
            ?: throw ErrorLoadingException("No title found")
        val poster = document.selectFirst("div.poster img")?.attr("src")?.ifBlank { null }
        val year = document.selectFirst(".year")?.text()?.toIntOrNull()
        val plot = document.selectFirst(".plot, .description")?.text()?.ifBlank { null }
        val tags = document.select(".genres a").map { it.text() }.ifEmpty { null }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
            this.year = year
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val sources = document.select("video source, iframe[src]").mapNotNull { element ->
            val src = element.attr("src").ifBlank { return@mapNotNull null }
            fixUrl(src)
        }

        var found = false
        sources.forEach { src ->
            if (src.contains(".mp4") || src.contains(".m3u8")) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = src,
                        referer = mainUrl,
                        type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8
                        else ExtractorLinkType.VIDEO
                    )
                )
                found = true
            }
        }

        return found
    }
}
