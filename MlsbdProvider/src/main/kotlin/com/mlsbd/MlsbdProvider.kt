package com.mlsbd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// --- TMDB API Constants ---
const val TMDB_API = "https://api.themoviedb.org/3"
const val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
const val TMDB_IMG = "https://image.tmdb.org/t/p/original"

// --- WordPress REST API Data Classes ---
data class WpPost(
    @JsonProperty("id")            val id: Int = 0,
    @JsonProperty("title")         val title: WpTitle = WpTitle(),
    @JsonProperty("link")          val link: String = "",
    @JsonProperty("date")          val date: String = "",
    @JsonProperty("content")       val content: WpContent? = null,
    @JsonProperty("nelio_content") val nelioContent: NelioContent? = null,
    @JsonProperty("yoast_head_json") val yoastHead: YoastHead? = null
)
data class WpTitle(@JsonProperty("rendered") val rendered: String = "")
data class WpContent(@JsonProperty("rendered") val rendered: String = "")
data class NelioContent(@JsonProperty("efiUrl") val efiUrl: String? = null)
data class YoastHead(@JsonProperty("thumbnailUrl") val thumbnailUrl: String? = null)

// --- TMDB Data Classes ---
data class TmdbImages(
    @JsonProperty("logos")     val logos: List<TmdbImage>? = null,
    @JsonProperty("backdrops") val backdrops: List<TmdbImage>? = null,
    @JsonProperty("posters")   val posters: List<TmdbImage>? = null
)
data class TmdbImage(
    @JsonProperty("file_path") val filePath: String? = null,
    @JsonProperty("iso_639_1") val lang: String? = null
)
data class TmdbFind(
    @JsonProperty("movie_results") val movies: List<TmdbResult>? = null,
    @JsonProperty("tv_results")    val tvShows: List<TmdbResult>? = null
)
data class TmdbResult(
    @JsonProperty("id")             val id: Int? = null,
    @JsonProperty("media_type")     val mediaType: String? = null,
    @JsonProperty("title")          val title: String? = null,
    @JsonProperty("name")           val name: String? = null,
    @JsonProperty("release_date")   val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null
)
data class TmdbSearch(@JsonProperty("results") val results: List<TmdbResult>? = null)
data class TmdbAssets(val poster: String?, val logo: String?, val backdrop: String?)

class MlsbdProvider : MainAPI() {
    override var mainUrl = "https://mlsbd.co"
    override var name = "MLSBD"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // WordPress REST API base
    private val api = "$mainUrl/wp-json/wp/v2"

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // --- Category IDs fetched from /wp-json/wp/v2/categories ---
    // Format: API URL (page will be appended) -> Section name
    override val mainPage = mainPageOf(
        "$api/posts?per_page=20&orderby=date&order=desc"              to "Latest Movies",
        "$api/posts?per_page=20&categories=13&orderby=date&order=desc" to "Bollywood",
        "$api/posts?per_page=20&categories=14&orderby=date&order=desc" to "Hollywood",
        "$api/posts?per_page=20&categories=12&orderby=date&order=desc" to "Bengali",
        "$api/posts?per_page=20&categories=70&orderby=date&order=desc" to "Dual Audio",
        "$api/posts?per_page=20&categories=69&orderby=date&order=desc" to "Hindi Dubbed",
        "$api/posts?per_page=20&categories=53&orderby=date&order=desc" to "Bangla Dubbed",
        "$api/posts?per_page=20&categories=45&orderby=date&order=desc" to "Korean",
        "$api/posts?per_page=20&categories=40&orderby=date&order=desc" to "TV Series",
        "$api/posts?per_page=20&categories=192&orderby=date&order=desc" to "Anime",
        "$api/posts?per_page=20&categories=15&orderby=date&order=desc" to "Animation",
        "$api/posts?per_page=20&categories=43802&orderby=date&order=desc" to "Klikk",
        "$api/posts?per_page=20&categories=59075&orderby=date&order=desc" to "Chorki",
        "$api/posts?per_page=20&categories=71676&orderby=date&order=desc" to "MX Player",
        "$api/posts?per_page=20&categories=35&orderby=date&order=desc" to "South Indian",
        "$api/posts?per_page=20&categories=2218&orderby=date&order=desc" to "Japanese",
        "$api/posts?per_page=20&categories=41&orderby=date&order=desc" to "Horror",
        "$api/posts?per_page=20&categories=28023&orderby=date&order=desc" to "Ullu",
        "$api/posts?per_page=20&categories=1816&orderby=date&order=desc" to "Unrated"
    )

    // ─── Title Helpers ────────────────────────────────────────────────────────

    private fun unescapeHtml(text: String): String =
        org.jsoup.parser.Parser.unescapeEntities(text, false)

    private fun getYearFromTitle(raw: String): Int? {
        Regex("\\((\\d{4})-\\d{4}\\)").find(raw)?.let { return it.groupValues[1].toIntOrNull() }
        return Regex("\\((\\d{4})\\)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun getDisplayTitle(raw: String): String {
        Regex("\\(\\d{4}-\\d{4}\\)").find(raw)?.let {
            return raw.substringBefore(it.value).trim() + " " + it.value
        }
        Regex("\\(\\d{4}\\)").find(raw)?.let {
            return raw.substringBefore(it.value).trim() + " " + it.value
        }
        return raw.trim()
    }

    private fun cleanTitleForTmdb(raw: String): String {
        val clean = raw.replace(Regex("^\\[.*?]\\s*"), "")
        Regex("\\(\\d{4}-\\d{4}\\)").find(clean)?.let { return clean.substringBefore(it.value).trim() }
        Regex("\\(\\d{4}\\)").find(clean)?.let { return clean.substringBefore(it.value).trim() }
        return clean.trim()
    }

    private fun isTvSeries(title: String, url: String): Boolean =
        title.contains("Season", true) || title.contains("Episode", true) ||
        url.contains("series", true)  || url.contains("season", true)

    // ─── TMDB Helpers ─────────────────────────────────────────────────────────

    private fun encodeUri(text: String): String =
        text.replace("%", "%25").replace(" ", "%20").replace("#", "%23")
            .replace("&", "%26").replace("?", "%3F").replace("=", "%3D")
            .replace(":", "%3A").replace("/", "%2F").replace("'", "%27")
            .replace("\"", "%22").replace(",", "%2C")

    private fun normalizeTitle(s: String?): String =
        s?.replace(Regex("[^a-zA-Z0-9]"), "")?.lowercase() ?: ""

    private fun getResultYear(r: TmdbResult): Int? =
        (r.releaseDate ?: r.firstAirDate)?.substringBefore("-")?.toIntOrNull()

    private fun pickBestResult(list: List<TmdbResult>, siteYear: Int?): TmdbResult? {
        if (list.isEmpty()) return null
        if (siteYear == null || list.size == 1) return list.first()
        return list.firstOrNull { kotlin.math.abs((getResultYear(it) ?: 0) - siteYear) <= 1 }
            ?: list.first()
    }

    private suspend fun fetchTmdbAssets(
        title: String,
        isSeries: Boolean,
        year: Int?,
        imdbId: String? = null
    ): TmdbAssets {
        return try {
            var tmdbId: Int? = null
            var mediaType = if (isSeries) "tv" else "movie"

            // Step 1: Try IMDB ID lookup
            if (imdbId?.startsWith("tt") == true) {
                val found = app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id")
                    .parsedSafe<TmdbFind>()
                val tvId    = found?.tvShows?.firstOrNull()?.id
                val movieId = found?.movies?.firstOrNull()?.id
                when {
                    isSeries && tvId != null    -> { tmdbId = tvId;    mediaType = "tv" }
                    !isSeries && movieId != null -> { tmdbId = movieId; mediaType = "movie" }
                    movieId != null              -> { tmdbId = movieId; mediaType = "movie" }
                    tvId != null                 -> { tmdbId = tvId;    mediaType = "tv" }
                }
            }

            // Step 2: Fallback to text search
            if (tmdbId == null) {
                val results = app.get("$TMDB_API/search/multi?api_key=$TMDB_KEY&query=${encodeUri(title)}")
                    .parsedSafe<TmdbSearch>()?.results
                    ?.filter { it.mediaType == "movie" || it.mediaType == "tv" }
                val norm = normalizeTitle(title)
                val exact = results?.filter {
                    normalizeTitle(it.title) == norm || normalizeTitle(it.name) == norm
                } ?: emptyList()
                val best = pickBestResult(exact, year) ?: run {
                    if (norm.length >= 5) {
                        val startsWith = results?.filter {
                            normalizeTitle(it.title ?: it.name).startsWith(norm)
                        } ?: emptyList()
                        pickBestResult(startsWith, year)
                    } else null
                }
                best?.let { tmdbId = it.id; mediaType = it.mediaType ?: mediaType }
            }

            if (tmdbId == null) return TmdbAssets(null, null, null)

            val images = app.get("$TMDB_API/$mediaType/$tmdbId/images?api_key=$TMDB_KEY")
                .parsedSafe<TmdbImages>()

            fun List<TmdbImage>?.pick(vararg langs: String?): TmdbImage? {
                if (this == null) return null
                for (lang in langs) {
                    firstOrNull { it.lang == lang }?.let { return it }
                }
                return firstOrNull()
            }

            TmdbAssets(
                poster   = images?.posters?.pick("en", null, "bn", "hi")?.filePath?.let { "$TMDB_IMG$it" },
                logo     = images?.logos?.pick("en", null, "bn", "hi")?.filePath?.let { "$TMDB_IMG$it" },
                backdrop = images?.backdrops?.pick(null, "en", "bn", "hi")?.filePath?.let { "$TMDB_IMG$it" }
            )
        } catch (e: Exception) {
            TmdbAssets(null, null, null)
        }
    }

    // ─── Poster Extraction from API post ──────────────────────────────────────

    /**
     * For listing/search cards: nelio CDN thumbnail (faster, no HTML parse needed)
     * Fallback: yoast thumbnailUrl
     */
    private fun getListingPoster(post: WpPost): String? =
        post.nelioContent?.efiUrl ?: post.yoastHead?.thumbnailUrl

    /**
     * For detail page: actual movie poster inside div.poster img
     * Parsed from content.rendered HTML (no extra HTTP request needed)
     * Fallback chain: HTML poster -> nelio -> yoast -> og:image
     */
    private fun getDetailPoster(contentDoc: Document, post: WpPost): String? =
        contentDoc.selectFirst("div.poster img")?.attr("src")
            ?: post.nelioContent?.efiUrl
            ?: post.yoastHead?.thumbnailUrl

    // ─── Main Page ────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val apiUrl = "${request.data}&page=$page"
        val posts = app.get(apiUrl, headers = ua, timeout = 30)
            .parsedSafe<List<WpPost>>() ?: emptyList()

        val items = posts.amap { post ->
            try {
                val rawTitle = unescapeHtml(post.title.rendered).trim()
                if (rawTitle.isBlank() || post.link.isBlank()) return@amap null

                val displayTitle = getDisplayTitle(rawTitle)
                val cleanTitle   = cleanTitleForTmdb(rawTitle)
                val year         = getYearFromTitle(rawTitle)
                val series       = isTvSeries(rawTitle, post.link)

                // Listing poster from API (no HTML scrape needed)
                val originalPoster = getListingPoster(post)

                val tmdb       = fetchTmdbAssets(cleanTitle, series, year)
                val finalPoster = tmdb.poster ?: originalPoster

                if (series) newTvSeriesSearchResponse(displayTitle, post.link, TvType.TvSeries) { posterUrl = finalPoster }
                else        newMovieSearchResponse(displayTitle, post.link, TvType.Movie)        { posterUrl = finalPoster }
            } catch (e: Exception) { null }
        }.filterNotNull()

        return newHomePageResponse(request.name, items, items.isNotEmpty() && page < 50)
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        // Use WordPress REST API search — much faster than HTML scrape
        val apiUrl = "$api/posts?search=$encoded&per_page=20&orderby=date&order=desc"
        val posts = app.get(apiUrl, headers = ua, timeout = 30)
            .parsedSafe<List<WpPost>>() ?: return emptyList()

        return posts.amap { post ->
            try {
                val rawTitle = unescapeHtml(post.title.rendered).trim()
                if (rawTitle.isBlank() || post.link.isBlank()) return@amap null

                val displayTitle = getDisplayTitle(rawTitle)
                val cleanTitle   = cleanTitleForTmdb(rawTitle)
                val year         = getYearFromTitle(rawTitle)
                val series       = isTvSeries(rawTitle, post.link)

                val originalPoster = getListingPoster(post)
                val tmdb           = fetchTmdbAssets(cleanTitle, series, year)
                val finalPoster    = tmdb.poster ?: originalPoster

                if (series) newTvSeriesSearchResponse(displayTitle, post.link, TvType.TvSeries) { posterUrl = finalPoster }
                else        newMovieSearchResponse(displayTitle, post.link, TvType.Movie)        { posterUrl = finalPoster }
            } catch (e: Exception) { null }
        }.filterNotNull()
    }

    // ─── Load ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        // Extract slug and fetch post via API — avoids loading the full HTML page
        val slug    = url.trimEnd('/').substringAfterLast('/')
        val apiUrl  = "$api/posts?slug=$slug"
        val posts   = app.get(apiUrl, headers = ua, timeout = 30).parsedSafe<List<WpPost>>()
        val apiPost = posts?.firstOrNull()

        // Parse content.rendered from API (same HTML as the detail page body)
        val contentHtml = apiPost?.content?.rendered ?: ""
        val contentDoc  = Jsoup.parse(contentHtml)

        // Fallback: load HTML page only if API returned nothing
        val fallbackDoc: Document? = if (apiPost == null) {
            app.get(url, headers = ua, timeout = 60).document
        } else null

        // Title
        val rawTitle = apiPost?.title?.rendered?.let { unescapeHtml(it).trim() }
            ?: fallbackDoc?.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim()
            ?: slug

        val displayTitle = getDisplayTitle(rawTitle)
        val cleanTitle   = cleanTitleForTmdb(rawTitle)
        val year         = getYearFromTitle(rawTitle)
        val series       = isTvSeries(rawTitle, url) ||
                           contentDoc.text().contains(Regex("(?i)(Episode \\d+|Season \\d+|Download Now Epi)"))

        // Poster: detail poster from content HTML (actual movie poster, not thumbnail)
        val originalPoster = if (apiPost != null) getDetailPoster(contentDoc, apiPost)
                             else fallbackDoc?.selectFirst("meta[property=og:image]")?.attr("content")

        // Description
        val description = (contentDoc.selectFirst("div.storyline")
            ?: fallbackDoc?.selectFirst("div.storyline"))
            ?.text()?.replace(Regex("(?i)Storyline\\s*:"), "")?.trim()

        // IMDB ID for precise TMDB lookup
        val imdbId = (contentDoc.selectFirst("a[href*='imdb.com/title']")
            ?: fallbackDoc?.selectFirst("a[href*='imdb.com/title']"))
            ?.attr("href")?.substringAfter("title/")?.substringBefore("/")
            ?.takeIf { it.startsWith("tt") }

        val tmdb        = fetchTmdbAssets(cleanTitle, series, year, imdbId)
        val finalPoster  = tmdb.poster ?: originalPoster
        val finalBackdrop = tmdb.backdrop ?: finalPoster
        val finalLogo    = tmdb.logo

        // Choose which document to parse for links
        // contentDoc = API content.rendered (preferred — no extra request)
        // fallbackDoc = full HTML page (used only when API failed)
        val linkDoc = if (contentDoc.body()?.childrenSize() != 0) contentDoc else fallbackDoc

        // ── TV Series ──────────────────────────────────────────────────────
        if (series) {
            val episodes    = mutableListOf<Episode>()
            val episodeMap  = mutableMapOf<Int, MutableList<String>>()
            val parsedSeason = Regex("(?i)Season[- ]?(\\d+)").find(rawTitle)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1
            var currentEpNum = 1

            for (tag in linkDoc?.body()?.children() ?: emptyList()) {
                val text = tag.text().trim()

                // Detect episode header (h2/h3/p/div containing "Episode N")
                Regex("(?i)(?:Epi|Ep|Episode)[- ]?(\\d+)").find(text)?.let {
                    if (tag.tagName() in listOf("h2", "h3", "h4", "p", "div", "strong", "b")) {
                        currentEpNum = it.groupValues[1].toInt()
                    }
                }

                val anchors = if (tag.tagName() == "a" && tag.hasAttr("href")) listOf(tag)
                              else tag.select("a[href]")

                for (a in anchors) {
                    val aText = a.text().trim()
                    val href  = a.attr("abs:href").ifBlank { a.attr("href") }
                    if (!href.startsWith("http")) continue

                    val isDownloadLink = isValidLink(href, aText)
                    if (!isDownloadLink) continue

                    val epNum = Regex("(?i)(?:Epi|Ep|Episode)[- ]?(\\d+)").find(aText)
                        ?.groupValues?.get(1)?.toInt() ?: currentEpNum

                    val quality = detectQuality(aText, tag.text())
                    episodeMap.getOrPut(epNum) { mutableListOf() }.add("$href|$quality")
                    currentEpNum = epNum
                }
            }

            episodeMap.forEach { (epNum, links) ->
                episodes.add(newEpisode(links.joinToString(",")) {
                    name     = "Episode $epNum"
                    episode  = epNum
                    season   = parsedSeason
                    posterUrl = finalPoster
                })
            }

            return newTvSeriesLoadResponse(
                displayTitle, url, TvType.TvSeries,
                episodes.distinctBy { it.data }.sortedBy { it.episode }
            ) {
                posterUrl           = finalPoster
                backgroundPosterUrl = finalBackdrop
                logoUrl             = finalLogo
                plot                = description
                this.year           = year
            }
        }

        // ── Movie ──────────────────────────────────────────────────────────
        val iframes = linkDoc?.select("iframe")
            ?.mapNotNull { it.attr("src").takeIf { s -> s.startsWith("http") } }
            ?.map { "$it|Unknown" } ?: emptyList()

        val links = linkDoc?.select("a")?.mapNotNull { a ->
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (!href.startsWith("http") || !isValidLink(href, a.text())) return@mapNotNull null
            "$href|${detectQuality(a.text(), "")}"
        } ?: emptyList()

        val dataStr = (iframes + links).distinct().joinToString(",")

        return newMovieLoadResponse(displayTitle, url, TvType.Movie, dataStr) {
            posterUrl           = finalPoster
            backgroundPosterUrl = finalBackdrop
            logoUrl             = finalLogo
            plot                = description
            this.year           = year
        }
    }

    // ─── Link Helpers ─────────────────────────────────────────────────────────

    private fun isValidLink(href: String, text: String): Boolean {
        val validHosts = listOf("savelinks", "gdflix", "hubcloud", "filepress", "mega",
                                "vimeo", "drive", "filebee", "minochinos", "luluvid",
                                "dsvplay", "playmogo", "morencius", "pixeldrain",
                                "filemoon", "vidmoly", "streamwish", "streamtape",
                                "doodstream", "gofile", "gdtot")
        return validHosts.any { href.contains(it, true) } ||
               text.contains("Download in", true) ||
               text.contains("Watch Online", true) ||
               text.contains("Episode", true) ||
               text.contains("Epi", true)
    }

    private fun detectQuality(text: String, parentText: String): String {
        val combined = "$text $parentText"
        return when {
            combined.contains("4K",    true) -> "4K"
            combined.contains("1080p", true) -> "1080p"
            combined.contains("720p",  true) -> "720p"
            combined.contains("480p",  true) -> "480p"
            else -> "Unknown"
        }
    }

    // ─── Load Links ───────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        fun qualityScore(q: String): Int = when {
            q.contains("720p",  true) -> 1
            q.contains("1080p", true) -> 2
            q.contains("480p",  true) -> 3
            q.contains("4K",    true) -> 4
            else -> 5
        }

        val sortedUrls = data.split(",")
            .filter { it.isNotBlank() }
            .sortedBy { qualityScore(it.substringAfterLast("|", "Unknown")) }

        // Strip extra info from extractor name for cleaner display
        val cleanCallback: (ExtractorLink) -> Unit = { link ->
            var newName = link.name
            if (newName.contains("] ")) newName = newName.substringBefore("] ") + "]"
            try {
                val field = link::class.java.getDeclaredField("name")
                field.isAccessible = true
                field.set(link, newName)
            } catch (e: Exception) { e.printStackTrace() }
            callback.invoke(link)
        }

        suspend fun invokeExtractor(targetUrl: String, referer: String?) {
            try {
                when {
                    targetUrl.contains("gdflix",     true) -> GDFlix().getUrl(targetUrl, referer, subtitleCallback, cleanCallback)
                    targetUrl.contains("hubcloud",   true) -> HubCloud().getUrl(targetUrl, referer, subtitleCallback, cleanCallback)
                    targetUrl.contains("filepress",  true) ||
                    targetUrl.contains("filebee",    true) -> FilePress().getUrl(targetUrl, referer, subtitleCallback, cleanCallback)
                    targetUrl.contains("minochinos", true) -> Minochinos().getUrl(targetUrl, referer, subtitleCallback, cleanCallback)
                    targetUrl.contains("luluvid",    true) -> Luluvid().getUrl(targetUrl, referer, subtitleCallback, cleanCallback)
                    targetUrl.contains("dsvplay",    true) ||
                    targetUrl.contains("playmogo",   true) -> loadExtractor(
                        targetUrl.replace("dsvplay.com", "dood.to").replace("playmogo.com", "dood.to"),
                        subtitleCallback, cleanCallback
                    )
                    targetUrl.contains("morencius",  true) -> loadExtractor(
                        targetUrl.replace("morencius.com", "filemoon.sx"),
                        subtitleCallback, cleanCallback
                    )
                    else -> loadExtractor(targetUrl, subtitleCallback, cleanCallback)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        sortedUrls.amap { item ->
            val parts = item.split("|")
            val url   = parts[0].trim()
            if (!url.startsWith("http")) return@amap

            if (url.contains("savelinks", true)) {
                // Resolve savelinks redirect page to real host links
                try {
                    val slHtml  = app.get(url, headers = ua, timeout = 60).text
                    val slDoc   = org.jsoup.Jsoup.parse(slHtml)
                    val urlRegex = Regex("(?i)https?://[^\\s\"'<]+")
                    val validHosts = listOf("gdflix", "hubcloud", "filepress", "minochinos",
                                           "luluvid", "dsvplay", "vimeo", "drive", "pixeldrain",
                                           "filemoon", "vidmoly", "streamwish", "streamtape",
                                           "doodstream", "gofile", "gdtot")
                    val aLinks   = slDoc.select("a").mapNotNull { it.attr("abs:href") }
                    val allLinks = (urlRegex.findAll(slHtml).map { it.value } + aLinks).distinct()
                    allLinks.amap { slUrl ->
                        if (validHosts.any { slUrl.contains(it, true) }) invokeExtractor(slUrl, url)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            } else {
                invokeExtractor(url, url)
            }
        }

        return true
    }
}