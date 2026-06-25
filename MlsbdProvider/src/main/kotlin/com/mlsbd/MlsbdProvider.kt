package com.mlsbd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MlsbdProvider : MainAPI() {
    override var mainUrl = "https://mlsbd.co"
    override var name = "MLSBD"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val ua = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Movies",
        "$mainUrl/category/bollywood-movies/" to "Bollywood",
        "$mainUrl/category/hollywood-movies/" to "Hollywood",
        "$mainUrl/category/bangla-movies/" to "Bengali",
        "$mainUrl/category/dual-audio-movies/" to "Dual Audio",
        "$mainUrl/category/hindi-dubbed-movies/" to "Hindi Dubbed",
        "$mainUrl/category/bangla-dubbed/" to "Bangla Dubbed",
        "$mainUrl/category/korean-movies/" to "Korean",
        "$mainUrl/category/tv-series/" to "TV Series",
        "$mainUrl/category/anime/" to "Anime",
        "$mainUrl/category/animation-movies/" to "Animation",
        "$mainUrl/category/klikk/" to "Klikk",
        "$mainUrl/category/chorki-originals/" to "Chorki",
        "$mainUrl/category/mx-player/" to "MX Player",
        "$mainUrl/category/south-indian-movies/" to "South Indian",
        "$mainUrl/category/foreign-language-film/japanese-movie/" to "Japanese",
        "$mainUrl/category/horror-movies/" to "Horror",
        "$mainUrl/category/unrated/ullu/" to "Ullu",
        "$mainUrl/category/unrated/" to "Unrated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url, headers = ua, timeout = 60).document
        val items = doc.select("div.single-post, article.main-post-area div.single-post").mapNotNull { el ->
            val a = el.selectFirst(".post-desc a, .thumb a") ?: return@mapNotNull null
            val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
            val title = el.selectFirst("h2.post-title")?.text()?.trim() ?: return@mapNotNull null
            val poster = el.selectFirst("div.thumb img")?.attr("src")
            val isSeries = title.contains("Season", true) || title.contains("Episode", true) || href.contains("series", true) || href.contains("season", true) || href.contains("episode", true)
            if (isSeries) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            else newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
        return newHomePageResponse(request.name, items, items.isNotEmpty() && page < 50)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url, headers = ua, timeout = 60).document
        val items = doc.select("div.single-post").mapNotNull { el ->
            val a = el.selectFirst(".post-desc a, .thumb a") ?: return@mapNotNull null
            val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
            val title = el.selectFirst("h2.post-title")?.text()?.trim() ?: return@mapNotNull null
            val poster = el.selectFirst("div.thumb img")?.attr("src")
            val isSeries = title.contains("Season", true) || title.contains("Episode", true) || href.contains("series", true) || href.contains("season", true) || href.contains("episode", true)
            if (isSeries) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            else newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = ua, timeout = 60).document
        val title = doc.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim() ?: doc.title().trim()
        var poster = doc.selectFirst("div.entry-content img.aligncenter, div.post-content img, div.content img")?.attr("src")
        if (poster == null || poster.contains("mlsbdshop")) {
            poster = doc.select("img").firstOrNull { it.attr("src").contains("uploads/images") }?.attr("src") ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        }

        var description = doc.selectFirst("meta[property=og:description]")?.attr("content")
        if (description == null || description.contains("Storyline :")) {
            description = doc.select("div.entry-content p, div.post-content p").firstOrNull { it.text().contains("Storyline") || it.text().contains("Director") }?.text()?.trim() ?: doc.title().trim()
        }

        val contentArea = doc.selectFirst("div.entry-content, div.post-content, div.content")
        val isSeries = title.contains("Episode", true) || title.contains("Season", true) || 
                       url.contains("episode", true) || url.contains("season", true) || 
                       (contentArea?.text()?.contains(Regex("(?i)(Download Now Epi|Download Episode|Episode \\d+)")) == true)

        if (isSeries && contentArea != null) {
            val episodes = mutableListOf<Episode>()
            var currentEpNum = 1
            val episodeMap = mutableMapOf<Int, MutableList<String>>()

            val seasonMatch = Regex("(?i)Season[- ]?(\\d+)").find(title)
            val parsedSeason = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

            for (tag in contentArea.children()) {
                val text = tag.text().trim()
                
                val headerEpMatch = Regex("(?i)(?:Epi|Ep|Episode)[- ]?(\\d+)").find(text)
                if (headerEpMatch != null && tag.tagName() in listOf("h2", "h3", "h4", "p", "div", "strong", "b")) {
                    currentEpNum = headerEpMatch.groupValues[1].toInt()
                }

                val links = if (tag.tagName() == "a" && tag.hasAttr("href")) listOf(tag) else tag.select("a[href]")
                
                for (a in links) {
                    val aText = a.text().trim()
                    val href = a.attr("abs:href")
                    
                    val isValid = href.contains("savelinks", true) || href.contains("gdflix", true) || 
                                  href.contains("hubcloud", true) || href.contains("drive", true) || 
                                  href.contains("filepress", true) || href.contains("mega", true) || 
                                  href.contains("vimeo", true) || aText.contains("Download in", true) || 
                                  aText.contains("Watch Online", true) || aText.contains("Episode", true) || 
                                  aText.contains("Epi", true)
                    
                    if (isValid) {
                        val linkEpMatch = Regex("(?i)(?:Epi|Ep|Episode)[- ]?(\\d+)").find(aText)
                        val epNumForLink = if (linkEpMatch != null) {
                            linkEpMatch.groupValues[1].toInt()
                        } else {
                            currentEpNum
                        }
                        
                        var quality = "Unknown"
                        if (aText.contains("720p", true)) quality = "720p"
                        else if (aText.contains("1080p", true)) quality = "1080p"
                        else if (aText.contains("480p", true)) quality = "480p"
                        else if (aText.contains("4K", true)) quality = "4K"
                        else {
                            val parentText = tag.text()
                            if (parentText.contains("720p", true)) quality = "720p"
                            else if (parentText.contains("1080p", true)) quality = "1080p"
                            else if (parentText.contains("480p", true)) quality = "480p"
                            else if (parentText.contains("4K", true)) quality = "4K"
                        }
                        
                        episodeMap.getOrPut(epNumForLink) { mutableListOf() }.add("$href|$quality")
                        currentEpNum = epNumForLink
                    }
                }
            }

            episodeMap.forEach { (epNum, links) ->
                episodes.add(newEpisode(links.joinToString(",")) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                    this.season = parsedSeason
                    this.posterUrl = poster
                })
            }
            
            val sortedEpisodes = episodes.distinctBy { it.data }.sortedBy { it.episode }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val iframes = doc.select("iframe").mapNotNull { it.attr("src") }.filter { it.startsWith("http") }.map { "$it|Unknown" }
            val links = doc.select("a").mapNotNull { a -> 
                val href = a.attr("abs:href")
                val text = a.text()
                if (href.contains("savelinks", true) || href.contains("filepress", true) || href.contains("gdflix", true) || href.contains("hubcloud", true) || href.contains("drive", true) || href.contains("mega", true) || href.contains("vimeo", true)) {
                    var quality = "Unknown"
                    if (text.contains("720p", true)) quality = "720p"
                    else if (text.contains("1080p", true)) quality = "1080p"
                    else if (text.contains("480p", true)) quality = "480p"
                    else if (text.contains("4K", true)) quality = "4K"
                    "$href|$quality"
                } else null
            }
            val dataStr = (iframes + links).distinct().joinToString(",")
            return newMovieLoadResponse(title, url, TvType.Movie, dataStr) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (data.isBlank()) return false
        val urls = data.split(",")

        fun getQualityScore(q: String): Int {
            return when {
                q.contains("720p", true) -> 1
                q.contains("1080p", true) -> 2
                q.contains("480p", true) -> 3
                q.contains("4K", true) -> 4
                else -> 5
            }
        }

        val sortedUrls = urls.sortedBy { getQualityScore(it.substringAfterLast("|", "Unknown")) }

        suspend fun invokeExtractor(targetUrl: String, referer: String?) {
            if (targetUrl.contains("gdflix", true)) GDFlix().getUrl(targetUrl, referer, subtitleCallback, callback)
            else if (targetUrl.contains("hubcloud", true)) HubCloud().getUrl(targetUrl, referer, subtitleCallback, callback)
            else if (targetUrl.contains("filepress", true) || targetUrl.contains("filebee", true)) FilePress().getUrl(targetUrl, referer, subtitleCallback, callback)
            else if (targetUrl.contains("minochinos", true)) Minochinos().getUrl(targetUrl, referer, subtitleCallback, callback)
            else if (targetUrl.contains("luluvid", true)) Luluvid().getUrl(targetUrl, referer, subtitleCallback, callback)
            else if (targetUrl.contains("dsvplay", true)) Dsvplay().getUrl(targetUrl, referer, subtitleCallback, callback)
            else loadExtractor(targetUrl, subtitleCallback, callback)
        }

        sortedUrls.forEach { item ->
            if (item.isBlank()) return@forEach
            val parts = item.split("|")
            val url = parts[0].trim()
            val qualityStr = if (parts.size > 1) parts[1] else "Unknown"

            if (url.startsWith("http")) {
                if (url.contains("savelinks", true)) {
                    try {
                        val slHtml = app.get(url, headers = ua, timeout = 60).text
                        val urlRegex = Regex("(?i)https?://[^\\s\"'<]+")
                        val validHosts = listOf("gdflix", "hubcloud", "filepress", "minochinos", "luluvid", "dsvplay", "vimeo", "drive", "pixeldrain", "filemoon", "vidmoly", "streamwish", "streamtape", "doodstream", "gofile", "gdtot")
                        
                        // Fallback to a tags as well
                        val doc = org.jsoup.Jsoup.parse(slHtml)
                        val aLinks = doc.select("a").mapNotNull { it.attr("abs:href") }
                        
                        val allLinks = (urlRegex.findAll(slHtml).map { it.value }.toList() + aLinks).distinct()
                        
                        allLinks.forEach { slUrl ->
                            if (validHosts.any { slUrl.contains(it, true) }) {
                                invokeExtractor(slUrl, url)
                            }
                        }
                    } catch (e: Exception) {}
                } else {
                    invokeExtractor(url, url)
                }
            }
        }
        return true
    }
}
