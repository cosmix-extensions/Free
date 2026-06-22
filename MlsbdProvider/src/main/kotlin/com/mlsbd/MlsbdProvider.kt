package com.mlsbd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MlsbdProvider : MainAPI() {
    override var mainUrl = "https://mlsbd.co"
    override var name = "MLSBD"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon
    )

    override val mainPage = mainPageOf(
        "/" to "Home",
        "/category/anime/" to "Anime",
        "/category/animation-movies/" to "Animation Movies",
        "/category/bangla-dubbed/" to "Bangla Dubbed",
        "/category/bangla-movies/" to "Bangla Movies",
        "/category/bollywood-movies/" to "Bollywood Movies",
        "/category/dual-audio-movies/" to "Dual Audio Movies",
        "/category/hoichoi/" to "Hoichoi",
        "/category/hindi-dubbed-movies/" to "Hindi Dubbed Movies",
        "/category/hollywood-movies/" to "Hollywood Movies",
        "/category/tv-series/" to "TV Series",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            mainUrl + request.data
        } else {
            if (request.data == "/") {
                "$mainUrl/page/$page/"
            } else {
                "$mainUrl${request.data}page/$page/"
            }
        }
        
        val doc = app.get(url).document
        val items = doc.select("div.single-post, div.cat-post, div.slider-post, div.recent-container").mapNotNull {
            it.toSearchResult()
        }
        
        // Remove duplicates if any (due to multiple selectors catching the same item or its parent)
        val uniqueItems = items.distinctBy { it.url }
        
        return newHomePageResponse(request.name, uniqueItems)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("h2 a, h3 a") ?: this.selectFirst("a") ?: return null
        val title = a.text()
        if (title.isBlank()) return null
        val href = fixUrl(a.attr("href"))
        
        val img = this.selectFirst("img")?.let { 
            it.attr("data-lazy-src").takeIf { it.isNotBlank() } ?: it.attr("data-src").takeIf { it.isNotBlank() } ?: it.attr("src")
        }
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = img
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        val items = doc.select("div.single-post, div.cat-post, div.recent-container").mapNotNull {
            it.toSearchResult()
        }
        return items.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: ""
        val poster = doc.selectFirst("div.entry-content img")?.attr("src") ?: ""
        val description = doc.selectFirst("div.entry-content p")?.text() ?: ""

        val isSeries = title.contains(Regex("S\\d+E\\d+", RegexOption.IGNORE_CASE)) || 
                       title.contains(Regex("Epi-\\d+", RegexOption.IGNORE_CASE))

        if (isSeries) {
            val matchRange = Regex("E(\\d+)-(\\d+)", RegexOption.IGNORE_CASE).find(title)
            val matchSingle = Regex("E(\\d+)", RegexOption.IGNORE_CASE).find(title)
            
            var startEp = 1
            if (matchRange != null) {
                startEp = matchRange.groupValues[1].toInt()
            } else if (matchSingle != null) {
                // Sometimes it's S16E72 and it has 72 episodes. We'll just count up from 1.
                startEp = 1
            }
            
            val eps = mutableListOf<Episode>()
            var currentEpNum = startEp
            val currentQualities = mutableListOf<String>()
            val currentLinks = mutableListOf<String>()
            
            val entryContent = doc.selectFirst("div.entry-content")
            if (entryContent != null) {
                val pTags = entryContent.select("p, span, div, h2, h3, h4")
                for (p in pTags) {
                    val a = p.selectFirst("a")
                    val href = a?.attr("href") ?: continue
                    if (href.contains("savelinks.me")) {
                        val text = p.text().lowercase()
                        val quality = when {
                            text.contains("4k") -> "4K"
                            text.contains("1080") -> "1080p"
                            text.contains("720") -> "720p"
                            text.contains("480") -> "480p"
                            else -> "HD"
                        }
                        
                        if (currentQualities.contains(quality)) {
                            if (currentLinks.isNotEmpty()) {
                                eps.add(Episode(currentLinks.joinToString(","), "Episode $currentEpNum", season = null, episode = currentEpNum))
                                currentEpNum++
                            }
                            currentQualities.clear()
                            currentLinks.clear()
                        }
                        currentQualities.add(quality)
                        currentLinks.add("$quality|$href")
                    }
                }
                if (currentLinks.isNotEmpty()) {
                    eps.add(Episode(currentLinks.joinToString(","), "Episode $currentEpNum", season = null, episode = currentEpNum))
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val links = mutableListOf<String>()
            val aTags = doc.select("a[href*='savelinks.me']")
            for (a in aTags) {
                val text = a.parent()?.text()?.lowercase() ?: ""
                val quality = when {
                    text.contains("4k") -> "4K"
                    text.contains("1080") -> "1080p"
                    text.contains("720") -> "720p"
                    text.contains("480") -> "480p"
                    else -> "HD"
                }
                links.add("$quality|${a.attr("href")}")
            }
            val dataStr = links.joinToString(",")
            return newMovieLoadResponse(title, url, TvType.Movie, dataStr) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = data.split(",")
        for (linkInfo in links) {
            val parts = linkInfo.split("|")
            if (parts.size < 2) continue
            val qualityStr = parts[0]
            val savelinkUrl = parts[1]
            
            try {
                val doc = app.get(savelinkUrl).document
                val hostLinks = doc.select("a[href^=http]").map { it.attr("href") }
                    .filter { !it.contains("savelinks.me") && !it.contains("mlsbd.co") && !it.contains("t.me") }
                
                for (hostLink in hostLinks) {
                    loadExtractor(hostLink, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }
}
