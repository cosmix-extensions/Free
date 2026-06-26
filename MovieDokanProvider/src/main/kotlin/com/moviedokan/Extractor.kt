package com.moviedokan

import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Document

class LinkShield : ExtractorApi() {
    override var name = "LinkShield"
    override var mainUrl = "https://linkshield.cfd"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, allowRedirects = true)
        val doc = response.document
        // Depending on linkshield, we might need to click a button or just get the iframe.
        // We will leave this as a basic pass-through for now. If it requires a POST, we handle it here.
        // For now, if the redirect automatically goes to dldokan, we just pass it to loadExtractor.
        val targetUrl = response.url
        if (targetUrl.contains("dldokan", true)) {
            com.lagradost.cloudstream3.utils.loadExtractor(targetUrl, subtitleCallback, callback)
        } else {
            // Further bypass logic if needed. Sometimes it's inside an href or window.location.
            val iframe = doc.selectFirst("iframe")?.attr("src")
            if (iframe != null) {
                com.lagradost.cloudstream3.utils.loadExtractor(if (iframe.startsWith("//")) "https:$iframe" else iframe, subtitleCallback, callback)
            }
        }
    }
}

class DLDokan : ExtractorApi() {
    override var name = "DLDokan"
    override var mainUrl = "https://dldokan.online"
    override val requiresReferer = false

    // DLDokan uses the exact same script as GDFlix
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).document
        val downloadLinks = doc.select("a.btn, a.button, a[href*='download']").mapNotNull { it.attr("href") }
        
        for (link in downloadLinks) {
            val fixedLink = if (link.startsWith("/")) "$mainUrl$link" else link
            if (fixedLink.contains("drive.google.com") || fixedLink.contains("video-downloads.googleusercontent.com")) {
                // Instant Download logic
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "Instant DL",
                        url = "$fixedLink#.mkv", // Force ExoPlayer to treat as video
                        referer = "", // Important: empty referer bypasses Google Drive block!
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO,
                        headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36")
                    )
                )
            } else if (fixedLink.contains("hubcloud") || fixedLink.contains("gdtot")) {
                com.lagradost.cloudstream3.utils.loadExtractor(fixedLink, subtitleCallback, callback)
            } else if (fixedLink.contains(".zip", true)) {
                // Fast Cloud logic
                val unwrapped = fixedLink.replace(".zip", "", true)
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "Fast Cloud",
                        url = unwrapped,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            } else {
                com.lagradost.cloudstream3.utils.loadExtractor(fixedLink, subtitleCallback, callback)
            }
        }
    }
}
