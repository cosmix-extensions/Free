package com.mlsbd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

// --- TMDB API Constants (Moved globally to prevent unresolved reference errors) ---
const val TMDB_API = "https://api.themoviedb.org/3"
const val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
const val TMDB_IMG = "https://image.tmdb.org/t/p/original"

// --- TMDB Data Classes ---
data class TmdbImages(
    @JsonProperty("logos") val logos: List<TmdbImage>? = null,
    @JsonProperty("backdrops") val backdrops: List<TmdbImage>? = null,
    @JsonProperty("posters") val posters: List<TmdbImage>? = null
)
data class TmdbImage(
    @JsonProperty("file_path") val filePath: String? = null,
    @JsonProperty("iso_639_1") val lang: String? = null
)
data class TmdbFind(
    @JsonProperty("movie_results") val movies: List<TmdbResult>? = null,
    @JsonProperty("tv_results") val tvShows: List<TmdbResult>? = null
)
data class TmdbResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null
)
data class TmdbSearch(
    @JsonProperty("results") val results: List<TmdbResult>? = null
)
data class TmdbAssets(val poster: String?, val logo: String?, val backdrop: String?)
// -------------------------

class MlsbdProvider : MainAPI() {
    override var mainUrl = "https://mlsbd.co"
    override var name = "MLSBD"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val ua = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    // --- Helper Functions for Title & TMDB Logic ---
    
    // Gets the year for TMDB. If format is (2006-2015), it extracts the first year (2006)
    private fun getYearFromTitle(rawTitle: String): Int? {
        val match = Regex("\\((\\d{4})(?:-\\d{4})?\\)").find(rawTitle)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    // Cuts everything after the year for clean Cloudstream UI display
    // e.g., "[18+] Movie Name (2006-2015) 1080p..." -> "[18+] Movie Name (2006-2015)"
    private fun getDisplayTitle(rawTitle: String): String {
        val match = Regex("\\(\\d{4}(?:-\\d{4})?\\)").find(rawTitle)
        return if (match != null) {
            rawTitle.substring(0, match.range.last + 1).trim()
        } else {
            rawTitle.trim()
        }
    }

    // Cleans title purely for TMDB searching (Removes [18+] and Year)
    private fun cleanTitleForTmdb(rawTitle: String): String {
        var clean = rawTitle
        
        // Remove starting brackets like [18+], [Web Series], etc.
        clean = clean.replace(Regex("^\\[.*?]\\s*"), "")
        
        // Remove (YYYY) or (YYYY-YYYY) and everything after it
        val match = Regex("\\(\\d{4}(?:-\\d{4})?\\)").find(clean)
        if (match != null) {
            clean = clean.substring(0, match.range.first)
        }
        return clean.trim()
    }

    private fun encodeUri(text: String): String {
        return text.replace("%", "%25").replace(" ", "%20").replace("#", "%23")
            .replace("&", "%26").replace("?", "%3F").replace("=", "%3D")
            .replace(":", "%3A").replace("/", "%2F").replace("'", "%27")
            .replace("\"", "%22").replace(",", "%2C")
    }

    private fun normalizeTitle(s: String?): String {
        return s?.replace(Regex("[^a-zA-Z0-9]"), "")?.lowercase() ?: ""
    }

    private fun getResultYear(result: TmdbResult): Int? {
        return (result.releaseDate ?: result.firstAirDate)?.substringBefore("-")?.toIntOrNull()
    }

    private fun yearMatches(tmdbYear: Int?, siteYear: Int?): Boolean {
        if (siteYear == null || tmdbYear == null) return true
        return Math.abs(tmdbYear - siteYear) <= 1
    }

    private fun pickBestResult(candidates: List<TmdbResult>, siteYear: Int?): TmdbResult? {
        if (candidates.isEmpty()) return null
        if (siteYear == null || candidates.size == 1) return candidates.first()
        return candidates.firstOrNull { yearMatches(getResultYear(it), siteYear) }
            ?: candidates.first()
    }

    private suspend fun fetchTmdbAssets(title: String, isSeries: Boolean, year: Int?, imdbId: String? = null): TmdbAssets {
        return try {
            var tmdbId: Int? = null
            var actualMediaType = if (isSeries) "tv" else "movie"

            // 1. Try finding by IMDB ID first if provided
            if (imdbId != null && imdbId.startsWith("tt")) {
                val findRes = app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id").parsedSafe<TmdbFind>()
                val tvId = findRes?.tvShows?.firstOrNull()?.id
                val movieId = findRes?.movies?.firstOrNull()?.id

                if (isSeries && tvId != null) { tmdbId = tvId; actualMediaType = "tv" }
                else if (!isSeries && movieId != null) { tmdbId = movieId; actualMediaType = "movie" }
                else if (movieId != null) { tmdbId = movieId; actualMediaType = "movie" }
                else if (tvId != null) { tmdbId = tvId; actualMediaType = "tv" }
            }

            // 2. Search by Title if IMDB ID failed or wasn't provided
            if (tmdbId == null) {
                val safeTitle = encodeUri(title)
                val searchRes = app.get("$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$safeTitle").parsedSafe<TmdbSearch>()
                val validResults = searchRes?.results?.filter { it.mediaType == "movie" || it.mediaType == "tv" }
                
                val normTitle = normalizeTitle(title)
                
                // Exact Match
                val exactCandidates = validResults?.filter { normalizeTitle(it.title) == normTitle || normalizeTitle(it.name) == normTitle } ?: emptyList()
                val exactMatch = pickBestResult(exactCandidates, year)

                if (exactMatch != null) {
                    tmdbId = exactMatch.id
                    actualMediaType = exactMatch.mediaType ?: actualMediaType
                } else {
                    // Starts-With Match
                    val startsWithCandidates = if (normTitle.length >= 5) {
                        validResults?.filter { normalizeTitle(it.title ?: it.name).startsWith(normTitle) } ?: emptyList()
                    } else emptyList()
                    
                    val startsWithMatch = pickBestResult(startsWithCandidates, year)
                    if (startsWithMatch != null) {
                        tmdbId = startsWithMatch.id
                        actualMediaType = startsWithMatch.mediaType ?: actualMediaType
                    }
                }
            }

            if (tmdbId == null) return TmdbAssets(null, null, null)

            // 3. Fetch Images based on ID
            val images = app.get("$TMDB_API/$actualMediaType/$tmdbId/images?api_key=$TMDB_KEY").parsedSafe<TmdbImages>()

            // Poster: en -> null -> bn -> hi -> first
            val poster = images?.posters?.firstOrNull { it.lang == "en" }
                ?: images?.posters?.firstOrNull { it.lang == null }
                ?: images?.posters?.firstOrNull { it.lang == "bn" }
                ?: images?.posters?.firstOrNull { it.lang == "hi" }
                ?: images?.posters?.firstOrNull()

            // Logo: en -> null -> bn -> hi -> first
            val logo = images?.logos?.firstOrNull { it.lang == "en" }
                ?: images?.logos?.firstOrNull { it.lang == null }
                ?: images?.logos?.firstOrNull { it.lang == "bn" }
                ?: images?.logos?.firstOrNull { it.lang == "hi" }
                ?: images?.logos?.firstOrNull()

            // Backdrop: null -> en -> bn -> hi -> first
            val backdrop = images?.backdrops?.firstOrNull { it.lang == null }
                ?: images?.backdrops?.firstOrNull { it.lang == "en" }
                ?: images?.backdrops?.firstOrNull { it.lang == "bn" }
                ?: images?.backdrops?.firstOrNull { it.lang == "hi" }
                ?: images?.backdrops?.firstOrNull()

            val posterUrl = poster?.filePath?.let { "$TMDB_IMG$it" }
            val logoUrl = logo?.filePath?.let { "$TMDB_IMG$it" }
            val backdropUrl = backdrop?.filePath?.let { "$TMDB_IMG$it" }

            TmdbAssets(posterUrl, logoUrl, backdropUrl)

        } catch (e: Exception) {
            TmdbAssets(null, null, null)
        }
    }

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
        
        val elements = doc.select("div.single-post, article.main-post-area div.single-post").toList()
        
        val items = elements.amap { el ->
            val a = el.selectFirst(".post-desc a, .thumb a") ?: return@amap null
            val href = a.attr("abs:href").ifBlank { return@amap null }
            
            val rawTitle = el.selectFirst("h2.post-title")?.text()?.trim() ?: return@amap null
            
            val displayTitle = getDisplayTitle(rawTitle)
            val cleanTitle = cleanTitleForTmdb(rawTitle)
            val year = getYearFromTitle(rawTitle)
            
            val originalPoster = el.selectFirst("div.thumb img")?.attr("src")
            val isSeries = rawTitle.contains("Season", true) || rawTitle.contains("Episode", true) || href.contains("series", true) || href.contains("season", true) || href.contains("episode", true)
            
            val tmdbAssets = fetchTmdbAssets(cleanTitle, isSeries, year)
            val finalPoster = tmdbAssets.poster ?: originalPoster
            
            if (isSeries) newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) { this.posterUrl = finalPoster }
            else newMovieSearchResponse(displayTitle, href, TvType.Movie) { this.posterUrl = finalPoster }
        }.filterNotNull()
        
        return newHomePageResponse(request.name, items, items.isNotEmpty() && page < 50)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url, headers = ua, timeout = 60).document
        
        val elements = doc.select("div.single-post").toList()
        val items = elements.amap { el ->
            val a = el.selectFirst(".post-desc a, .thumb a") ?: return@amap null
            val href = a.attr("abs:href").ifBlank { return@amap null }
            
            val rawTitle = el.selectFirst("h2.post-title")?.text()?.trim() ?: return@amap null
            
            val displayTitle = getDisplayTitle(rawTitle)
            val cleanTitle = cleanTitleForTmdb(rawTitle)
            val year = getYearFromTitle(rawTitle)
            
            val originalPoster = el.selectFirst("div.thumb img")?.attr("src")
            val isSeries = rawTitle.contains("Season", true) || rawTitle.contains("Episode", true) || href.contains("series", true) || href.contains("season", true) || href.contains("episode", true)
            
            val tmdbAssets = fetchTmdbAssets(cleanTitle, isSeries, year)
            val finalPoster = tmdbAssets.poster ?: originalPoster
            
            if (isSeries) newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) { this.posterUrl = finalPoster }
            else newMovieSearchResponse(displayTitle, href, TvType.Movie) { this.posterUrl = finalPoster }
        }.filterNotNull()
        
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = ua, timeout = 60).document
        
        val rawTitle = doc.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim() ?: doc.title().trim()
        
        val displayTitle = getDisplayTitle(rawTitle)
        val cleanTitle = cleanTitleForTmdb(rawTitle)
        val year = getYearFromTitle(rawTitle)

        var originalPoster = doc.selectFirst("div.entry-content img.aligncenter, div.post-content img, div.content img")?.attr("src")
        if (originalPoster == null || originalPoster.contains("mlsbdshop")) {
            originalPoster = doc.select("img").firstOrNull { it.attr("src").contains("uploads/images") }?.attr("src") ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        }

        // Storyline Extraction
        var description = doc.selectFirst("div.storyline")?.text()
            ?.replace(Regex("(?i)Storyline\\s*:"), "")?.trim()

        if (description.isNullOrBlank()) {
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            if (description == null || description.contains("Storyline :")) {
                description = doc.select("div.entry-content p, div.post-content p").firstOrNull { it.text().contains("Storyline") || it.text().contains("Director") }?.text()?.trim() ?: doc.title().trim()
            }
        }

        // IMDB ID Extraction
        val imdbId = doc.selectFirst("a[href*='imdb.com/title']")?.attr("href")
            ?.substringAfter("title/")?.substringBefore("/")?.takeIf { it.startsWith("tt") }

        val contentArea = doc.selectFirst("div.entry-content, div.post-content, div.content")
        val isSeries = rawTitle.contains("Episode", true) || rawTitle.contains("Season", true) || 
                       url.contains("episode", true) || url.contains("season", true) || 
                       (contentArea?.text()?.contains(Regex("(?i)(Download Now Epi|Download Episode|Episode \\d+)")) == true)

        // Fetching TMDB Assets
        val tmdbAssets = fetchTmdbAssets(cleanTitle, isSeries, year, imdbId)
        
        val finalPoster = tmdbAssets.poster ?: originalPoster
        val finalBackdrop = tmdbAssets.backdrop ?: finalPoster
        val finalLogo = tmdbAssets.logo

        if (isSeries && contentArea != null) {
            val episodes = mutableListOf<Episode>()
            var currentEpNum = 1
            val episodeMap = mutableMapOf<Int, MutableList<String>>()

            val seasonMatch = Regex("(?i)Season[- ]?(\\d+)").find(rawTitle)
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
                    this.posterUrl = finalPoster
                })
            }
            
            val sortedEpisodes = episodes.distinctBy { it.data }.sortedBy { it.episode }

            return newTvSeriesLoadResponse(displayTitle, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.logoUrl = finalLogo
                this.plot = description
                this.year = year
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
            
            return newMovieLoadResponse(displayTitle, url, TvType.Movie, dataStr) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.logoUrl = finalLogo
                this.plot = description
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
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

        val customCallback: (ExtractorLink) -> Unit = { link ->
            var newName = link.name
            
            if (newName.contains("] ")) {
                newName = newName.substringBefore("] ") + "]"
            }
            
            try {
                val field = link::class.java.getDeclaredField("name")
                field.isAccessible = true
                field.set(link, newName)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            callback.invoke(link)
        }

        suspend fun invokeExtractor(targetUrl: String, referer: String?) {
            try {
                if (targetUrl.contains("gdflix", true)) GDFlix().getUrl(targetUrl, referer, subtitleCallback, customCallback)
                else if (targetUrl.contains("hubcloud", true)) {
                    HubCloud().getUrl(targetUrl, referer, subtitleCallback, customCallback)
                }
                else if (targetUrl.contains("filepress", true) || targetUrl.contains("filebee", true)) {
                    FilePress().getUrl(targetUrl, referer, subtitleCallback, customCallback)
                }
                else if (targetUrl.contains("minochinos", true)) {
                    Minochinos().getUrl(targetUrl, referer, subtitleCallback, customCallback)
                }
                else if (targetUrl.contains("luluvid", true)) {
                    Luluvid().getUrl(targetUrl, referer, subtitleCallback, customCallback)
                }
                else if (targetUrl.contains("dsvplay", true) || targetUrl.contains("playmogo", true)) {
                    loadExtractor(targetUrl.replace("dsvplay.com", "dood.to").replace("playmogo.com", "dood.to"), subtitleCallback, customCallback)
                }
                else if (targetUrl.contains("morencius", true)) {
                    loadExtractor(targetUrl.replace("morencius.com", "filemoon.sx"), subtitleCallback, customCallback)
                }
                else loadExtractor(targetUrl, subtitleCallback, customCallback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        sortedUrls.amap { item ->
            if (item.isNotBlank()) {
                val parts = item.split("|")
                val url = parts[0].trim()

                if (url.startsWith("http")) {
                    if (url.contains("savelinks", true)) {
                        try {
                            val slHtml = app.get(url, headers = ua, timeout = 60).text
                            val urlRegex = Regex("(?i)https?://[^\\s\"'<]+")
                            val validHosts = listOf("gdflix", "hubcloud", "filepress", "minochinos", "luluvid", "dsvplay", "vimeo", "drive", "pixeldrain", "filemoon", "vidmoly", "streamwish", "streamtape", "doodstream", "gofile", "gdtot")
                            
                            val doc = org.jsoup.Jsoup.parse(slHtml)
                            val aLinks = doc.select("a").mapNotNull { it.attr("abs:href") }
                            
                            val allLinks = (urlRegex.findAll(slHtml).map { it.value }.toList() + aLinks).distinct()
                            
                            allLinks.amap { slUrl ->
                                if (validHosts.any { slUrl.contains(it, true) }) {
                                    invokeExtractor(slUrl, url)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else {
                        invokeExtractor(url, url)
                    }
                }
            }
        }
        
        return true
    }
}
