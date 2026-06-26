package com.moviedokan

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MovieDokanProvider : MainAPI() {
    override var mainUrl = "https://moviedokan.co"
    override var name = "MovieDokan"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "Movies",
        "$mainUrl/tvshows/page/" to "TV Shows"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("article.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        val isTvSeries = href.contains("/tvshows/")

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.item, div.result-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.wp-content p")?.text()
        
        val isTvSeries = url.contains("/tvshows/")
        
        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            
            // The HTML has a complex layout like [ 480p ] [ 720p ] and buttons.
            // We'll scrape all the 'DOWNLOAD NOW' or 'links/' anchors
            val links = document.select("a[href*='links/']")
            links.forEachIndexed { index, element ->
                val link = element.attr("href")
                // Determine the name based on the previous sibling or parent elements
                var epName = "Link ${index + 1}"
                
                var current: Element? = element
                while (current != null) {
                    val prev = current.previousElementSibling()
                    if (prev != null && prev.text().contains("p]")) {
                        epName = prev.text()
                        break
                    }
                    if (prev != null && prev.text().contains("Episode", true)) {
                        epName = prev.text() + " " + epName
                    }
                    current = current.parent()
                }
                
                episodes.add(
                    Episode(
                        data = link,
                        name = epName,
                        season = 1,
                        episode = index + 1
                    )
                )
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Data could be the movie URL or the links/ URL
        val urlToFetch = if (data.contains("/links/")) data else {
            app.get(data).document.selectFirst("a[href*='links/']")?.attr("href") ?: return false
        }
        
        val doc = app.get(urlToFetch).document
        // Find the continue link or iframe
        val iframe = doc.selectFirst("iframe")?.attr("src")
        if (iframe != null) {
            val finalUrl = if (iframe.startsWith("//")) "https:$iframe" else iframe
            loadExtractor(finalUrl, subtitleCallback, callback)
            return true
        }
        
        val linkshield = doc.selectFirst("a[href*='linkshield']")?.attr("href")
        if (linkshield != null) {
            loadExtractor(linkshield, subtitleCallback, callback)
            return true
        }

        return true
    }
}
