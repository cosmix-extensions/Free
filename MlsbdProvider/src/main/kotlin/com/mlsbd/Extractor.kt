package com.mlsbd

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

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
        try {
            val doc = app.get(url).document
            val validLinks = doc.select("a").mapNotNull { it.attr("abs:href") }.filter { it.startsWith("http") }
            for (link in validLinks) {
                if (link.contains("r2.dev") || link.contains("cloudflare") || link.contains("worker") || link.contains("drive") || link.contains("download")) {
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            "GDFlix Direct",
                            link,
                            this.mainUrl,
                            Qualities.Unknown.value,
                            ExtractorLinkType.VIDEO
                        )
                    )
                } else if (!link.contains("gdflix")) {
                    loadExtractor(link, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        try {
            val hcDoc = app.get(url).document
            val genLink = hcDoc.selectFirst("a[href*=hubcloud]")?.attr("abs:href")
            if (genLink != null) {
                val genDoc = app.get(genLink).document
                val validLinks = genDoc.select("a").mapNotNull { it.attr("abs:href") }.filter { it.startsWith("http") }
                for (link in validLinks) {
                    if (link.contains("hubcloud.cx") || link.contains("r2.dev") || link.contains("cloudflare") || link.contains("worker") || link.contains("drive")) {
                        callback.invoke(
                            ExtractorLink(
                                this.name,
                                "HubCloud Direct",
                                link,
                                this.mainUrl,
                                Qualities.Unknown.value,
                                ExtractorLinkType.VIDEO
                            )
                        )
                    } else if (!link.contains("hubcloud")) {
                        loadExtractor(link, subtitleCallback, callback)
                    }
                }
            } else {
                val validLinks = hcDoc.select("a").mapNotNull { it.attr("abs:href") }.filter { it.startsWith("http") }
                for (link in validLinks) {
                    if (link.contains("r2.dev") || link.contains("cloudflare") || link.contains("worker")) {
                        callback.invoke(
                            ExtractorLink(
                                this.name,
                                "HubCloud Direct",
                                link,
                                this.mainUrl,
                                Qualities.Unknown.value,
                                ExtractorLinkType.VIDEO
                            )
                        )
                    } else if (!link.contains("hubcloud")) {
                        loadExtractor(link, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
