package com.mlsbd

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker

class Minochinos : VidhideExtractor() {
    override var name = "Minochinos"
    override var mainUrl = "https://minochinos.com"
}

class Luluvid : StreamWishExtractor() {
    override var name = "Luluvid"
    override var mainUrl = "https://luluvid.com"
}

class Dsvplay : StreamWishExtractor() {
    override var name = "Dsvplay"
    override var mainUrl = "https://dsvplay.com"
}

class FilePress : ExtractorApi() {
    override val name = "FilePress"
    override val mainUrl = "https://filepress.wiki"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.post(
                url.replace("/file/", "/api/file/down/"),
                data = mapOf("id" to url.substringAfterLast("/"))
            ).parsedSafe<Map<String, String>>()
            
            response?.get("data")?.let { directUrl ->
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        "FilePress Direct",
                        directUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {}
    }
}

class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        suspend fun resolve(targetUrl: String, depth: Int = 0) {
            if (depth > 2) return
            try {
                val doc = app.get(targetUrl).document
                val validLinks = doc.select("a").mapNotNull { it.attr("abs:href") }.filter { it.startsWith("http") }
                
                val unpacked = doc.select("script").mapNotNull { it.data() }.firstOrNull { it.contains("eval(function(p,a,c,k,e,d)") }
                if (unpacked != null) {
                    val decoded = JsUnpacker(unpacked).unpack()
                    // Extract urls from decoded js
                    val jsLinks = Regex("https?://[^\"']+").findAll(decoded ?: "").map { it.value }.toList()
                    jsLinks.forEach { link ->
                        if (link.contains("r2.dev") || link.contains("worker")) {
                            callback.invoke(newExtractorLink(name, "GDFlix [Packed]", link, ExtractorLinkType.VIDEO) { quality = Qualities.Unknown.value })
                        }
                    }
                }

                for (link in validLinks) {
                    if (link.contains("r2.dev") || link.contains("cloudflare") || link.contains("worker") || link.contains("drive") || link.contains("download")) {
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                "GDFlix Direct",
                                link,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = this@GDFlix.mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    } else if (link.contains("gdflix", true) && link != targetUrl) {
                        resolve(link, depth + 1)
                    } else if (!link.contains("gdflix")) {
                        loadExtractor(link, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {}
        }
        resolve(url)
    }
}

class HubCloud : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.fyi"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        suspend fun resolve(targetUrl: String, depth: Int = 0) {
            if (depth > 2) return
            try {
                val doc = app.get(targetUrl).document
                val validLinks = doc.select("a").mapNotNull { it.attr("abs:href") }.filter { it.startsWith("http") }

                val unpacked = doc.select("script").mapNotNull { it.data() }.firstOrNull { it.contains("eval(function(p,a,c,k,e,d)") }
                if (unpacked != null) {
                    val decoded = JsUnpacker(unpacked).unpack()
                    val jsLinks = Regex("https?://[^\"']+").findAll(decoded ?: "").map { it.value }.toList()
                    jsLinks.forEach { link ->
                        if (link.contains("r2.dev") || link.contains("worker")) {
                            callback.invoke(newExtractorLink(name, "HubCloud [Packed]", link, ExtractorLinkType.VIDEO) { quality = Qualities.Unknown.value })
                        }
                    }
                }

                for (link in validLinks) {
                    if (link.contains("hubcloud.cx") || link.contains("r2.dev") || link.contains("cloudflare") || link.contains("worker") || link.contains("drive")) {
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                "HubCloud Direct",
                                link,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = this@HubCloud.mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    } else if (link.contains("hubcloud", true) && !link.contains("cx") && link != targetUrl) {
                        resolve(link, depth + 1)
                    } else if (!link.contains("hubcloud")) {
                        loadExtractor(link, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {}
        }
        resolve(url)
    }
}
