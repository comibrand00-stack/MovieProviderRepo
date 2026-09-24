package com.example.movieprovider

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VideoType
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toAppContext
import com.lagradost.cloudstream3.utils.IOUtils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.*

class MovieProvider : MainAPI() {
    override var name: String = "MovieProvider"
    override var mainUrl: String = "https://example-movies.com"
    override var supportedTypes: Set<TvType> = setOf(TvType.Movie)
    override var hasMainPage: Boolean = true
    override var lang: String = "en"
    override var hasChromecastSupport: Boolean = true
    override var hasDownloadSupport: Boolean = true
    override var providerType: ProviderType = ProviderType.DirectProvider
    override val vpnStatus: VPNStatus = VPNStatus.None
    override val loadTimeoutMs: Long = 30000
    override val loadLinksTimeoutMs: Long = 60000

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document

        val sliderItems = document.select("div.slider-item").mapNotNull { element ->
            val title = element.select("h2").text() ?: return@mapNotNull null
            val url = element.select("a").attr("href") ?: return@mapNotNull null
            val poster = element.select("img").attr("src") ?: return@mapNotNull null
            MovieSearchResponse(
                title,
                url,
                this.name,
                TvType.Movie,
                poster
            )
        }

        val recentReleases = document.select("div.recent-release").mapNotNull { element ->
            val title = element.select("h3").text() ?: return@mapNotNull null
            val url = element.select("a").attr("href") ?: return@mapNotNull null
            val poster = element.select("img").attr("src") ?: return@mapNotNull null
            MovieSearchResponse(
                title,
                url,
                this.name,
                TvType.Movie,
                poster
            )
        }

        val allRequests = listOf(
            Pair("Popular Movies", sliderItems),
            Pair("Recent Releases", recentReleases)
        )

        return newHomePageResponse(allRequests)
    }

    override suspend fun search(query: String): List<MovieSearchResponse>? {
        val document = app.get("$mainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}").document

        return document.select("div.search-result").mapNotNull { element ->
            val title = element.select("h3").text() ?: return@mapNotNull null
            val url = element.select("a").attr("href") ?: return@mapNotNull null
            val poster = element.select("img").attr("src") ?: return@mapNotNull null
            val year = element.select("span.year")?.text()?.toIntOrNull()

            MovieSearchResponse(
                title,
                url,
                this.name,
                TvType.Movie,
                poster,
                year = year
            )
        }.ifEmpty { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.select("h1.movie-title").text() ?: throw ErrorLoadingException("No Title")
        val posterUrl = document.select("div.poster img").attr("src")
        val year = document.select("span.year")?.text()?.toIntOrNull()
        val plot = document.select("div.plot").text().trim()
        val rating = document.select("span.rating")?.text()?.toFloatOrNull()
        val duration = document.select("span.duration")?.text()?.trim()
        val tags = document.select("div.genres a").mapNotNull { it.text() }
        val cast = document.select("div.cast a").mapNotNull { it.text() }
        val trailerUrl = document.select("iframe.trailer").attr("src")

        val episodesUrl = if (url.contains("/movie/")) {
            "$mainUrl/api/movie/episodes${url.substring(url.indexOfLast { it == '/' })}"
        } else url

        val episodesDocument = app.get(episodesUrl).document
        val sources = episodesDocument.select("a.source-link").mapNotNull { element ->
            val sourceId = element.attr("data-id") ?: return@mapNotNull null
            "$url.$sourceId"
        }

        val recommendations = document.select("div.recommendations .flw-item").mapNotNull { element ->
            val recTitle = element.select("h3").text() ?: return@mapNotNull null
            val recUrl = element.select("a").attr("href") ?: return@mapNotNull null
            val recPoster = element.select("img").attr("src") ?: return@mapNotNull null
            MovieSearchResponse(
                recTitle,
                recUrl,
                this.name,
                TvType.Movie,
                recPoster,
                year = null
            )
        }

        return newMovieLoadResponse(title, url, TvType.Movie, sources) {
            this.posterUrl = posterUrl
            this.year = year
            this.plot = plot
            this.rating = rating
            addDuration(duration)
            this.tags = tags
            addActors(cast)
            this.recommendations = recommendations
            addTrailer(trailerUrl)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val videoSources = document.select("video source").mapNotNull { element ->
            val src = element.attr("src") ?: return@mapNotNull null
            val type = element.attr("type")?.let { VideoType.fromExtension(it) } ?: VideoType.Unknown
            src to type
        }

        if (videoSources.isEmpty()) {
            val embedUrl = document.select("iframe.player").attr("src")
            if (!embedUrl.isNullOrEmpty()) {
                val embedDoc = app.get(embedUrl).document
                val mp4Src = embedDoc.select("video source").attr("src")
                if (!mp4Src.isNullOrEmpty()) {
                    callback(ExtractorLink(this.name, this.name, mp4Src, "", VideoType.Unknown))
                    return true
                }
            }
            return false
        }

        videoSources.forEach { (src, type) ->
            callback(ExtractorLink(this.name, this.name, src, "", type))
        }
        return true
    }
}
