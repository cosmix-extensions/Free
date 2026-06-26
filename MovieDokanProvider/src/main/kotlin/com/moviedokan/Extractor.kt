package com.moviedokan

import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
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
        val targetUrl = response.url
        
        if (targetUrl.contains("dldokan", true)) {
            com.lagradost.cloudstream3.utils.loadExtractor(targetUrl, subtitleCallback, callback)
        } else {
            val html = doc.html()
            val dldokanLink = doc.selectFirst("a[href*='dldokan']")?.attr("href")
                ?: Regex("""https?://[^\s\"\'<>]*dldokan[^\s\"\'<>]*""").find(html)?.value

            if (dldokanLink != null) {
                com.lagradost.cloudstream3.utils.loadExtractor(dldokanLink, subtitleCallback, callback)
            } else {
                val iframe = doc.selectFirst("iframe")?.attr("src")
                if (iframe != null) {
                    com.lagradost.cloudstream3.utils.loadExtractor(if (iframe.startsWith("//")) "https:$iframe" else iframe, subtitleCallback, callback)
                }
            }
        }
    }
}

class DLDokan : ExtractorApi() {
    override var name = "DLDokan"
    override var mainUrl = "https://dldokan"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var currentUrl = url
        var html = app.get(currentUrl).document.html()

        // 0. Extract File ID from the URL and attempt to get the direct video from player.php
        val fileId = Regex("""/file/([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1)
        if (fileId != null) {
            runCatching {
                val playerUrl = "https://dldokan.online/download/player.php?token=$fileId"
                val playerHtml = app.get(
                    playerUrl,
                    headers = mapOf("Referer" to url)
                ).document.html()

                val videoUrl = Regex("""videoURL\s*=\s*[\"\'](https?://[^\"\']+)[\"\']""").find(playerHtml)?.groupValues?.get(1)
                if (videoUrl != null) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "Direct Stream",
                            url = videoUrl,
                            referer = url,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO,
                            headers = emptyMap()
                        )
                    )
                }
            }
        }

        // 1. Generate Token (Bypass Generate Download Links button)
        runCatching {
            val tokenUrl = if (currentUrl.contains("?")) "$currentUrl&generate_links=1" else "$currentUrl?generate_links=1"
            val tokenRes = app.get(
                tokenUrl,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<DLDokanTokenResponse>()
            
            if (tokenRes?.success == true && tokenRes.download_url != null) {
                currentUrl = tokenRes.download_url
                html = app.get(currentUrl).document.html()
            }
        }
        
        // 2. Extract Drive ID from the tokenized page
        val driveId = Regex("""drive_?id[\"\'\s]*[:=][\"\'\s]*([^\"\']+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""id=([a-zA-Z0-9_-]{25,})""").find(html)?.groupValues?.get(1)

        if (driveId != null) {
            // 1. Native GDrive Bypass (Best Quality)
            com.lagradost.cloudstream3.utils.loadExtractor("https://drive.google.com/file/d/$driveId/view", subtitleCallback, callback)

            // 2. Extract Worker Links
            val workersStr = Regex("""WORKER_LINKS\s*=\s*\[(.*?)\]""").find(html)?.groupValues?.get(1)
            if (!workersStr.isNullOrBlank()) {
                val workers = Regex("""['"]([^'"]+)['"]""").findAll(workersStr).map { it.groupValues[1] }.toList()
                workers.forEach { worker ->
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "Fast Cloud Worker",
                            url = "$worker/direct.aspx?id=$driveId",
                            referer = "",
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO,
                            headers = emptyMap()
                        )
                    )
                }
            }

            // 3. Extract Filepress Links
            val filepressStr = Regex("""FILEPRESS_LINKS\s*=\s*\[(.*?)\]""").find(html)?.groupValues?.get(1)
            if (!filepressStr.isNullOrBlank()) {
                val filepress = Regex("""['"]([^'"]+)['"]""").findAll(filepressStr).map { it.groupValues[1] }.toList()
                filepress.forEach { fp ->
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "Filepress",
                            url = "$fp?id=$driveId",
                            referer = "",
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO,
                            headers = emptyMap()
                        )
                    )
                }
            }
        }
        
        // 4. Also fallback to any normal download links they might have on the tokenized page
        val document = org.jsoup.Jsoup.parse(html)
        val downloadLinks = document.select("a.btn, a.button, a[href*='download']").mapNotNull { it.attr("href") }
        for (link in downloadLinks) {
            val fixedLink = if (link.startsWith("/")) "https://dldokan.online$link" else link
            if (fixedLink.contains("drive.google.com") || fixedLink.contains("video-downloads.googleusercontent.com")) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "Instant DL",
                        url = "$fixedLink#.mkv",
                        referer = "",
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO,
                        headers = mapOf("User-Agent" to "Mozilla/5.0")
                    )
                )
            } else if (fixedLink.contains("hubcloud") || fixedLink.contains("gdtot")) {
                com.lagradost.cloudstream3.utils.loadExtractor(fixedLink, subtitleCallback, callback)
            } else if (fixedLink.contains(".zip", true)) {
                val unwrapped = fixedLink.replace(".zip", "", true)
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "Fast Cloud",
                        url = unwrapped,
                        referer = "https://dldokan.online",
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }
        }
    }
}


data class DLDokanTokenResponse(
    @JsonProperty("success") val success: Boolean?,
    @JsonProperty("download_url") val download_url: String?,
    @JsonProperty("message") val message: String?
)
