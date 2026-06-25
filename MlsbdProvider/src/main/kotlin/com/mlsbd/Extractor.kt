package com.mlsbd

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.WebViewResolver

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import kotlinx.coroutines.delay

class Minochinos : VidhideExtractor() {
    override var name = "Minochinos"
    override var mainUrl = "https://minochinos.com"
    
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.replace("/file/", "/v/")
        super.getUrl(embedUrl, referer, subtitleCallback, callback)
    }
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
    override var name = "FilePress"
    override var mainUrl = "https://filepress.wiki"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val fileId = url.trimEnd('/').split("/").last()
            val host = java.net.URI(url).host
            val interceptor = CloudflareKiller()
            val apiPost = "https://$host/api/file/downlaod/"
            val apiPost2 = "https://$host/api/file/downlaod2/"
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "application/json, text/plain, */*",
                "Origin" to "https://$host",
                "Referer" to url
            )

            val methodsToTry = listOf("indexDownlaod", "dotFlixDownlaod", "telegramDownload", "TelegramDirectDownlaod", "cloudR2Downlaod", "cloudDownlaod")
            var extracted = false
            for (method in methodsToTry) {
                if (extracted) break
                try {
                    val initialPost = app.post(apiPost, headers = headers, json = mapOf("id" to fileId, "method" to method, "captchaValue" to ""), interceptor = interceptor).parsedSafe<Map<String, Any?>>()
                    val dataNode = initialPost?.get("data")
                    
                    var downloadId: String? = null
                    var finalUrl: String? = null

                    if (dataNode is String) {
                        if (dataNode.startsWith("http")) finalUrl = dataNode
                        else downloadId = dataNode
                    } else if (dataNode is Map<*, *>) {
                        var status = dataNode["status"]?.toString() ?: continue
                        downloadId = dataNode["downloadId"]?.toString()
                        
                        var attempts = 0
                        while (status != "completed" && status != "failed" && attempts < 15) {
                            delay(3000)
                            val poll = app.post(apiPost, headers = headers, json = mapOf("id" to fileId, "method" to method, "captchaValue" to ""), interceptor = interceptor).parsedSafe<Map<String, Any?>>()
                            val pollData = poll?.get("data") as? Map<String, Any?>
                            status = pollData?.get("status")?.toString() ?: "failed"
                            downloadId = pollData?.get("downloadId")?.toString()
                            attempts++
                        }
                    }

                    if (downloadId != null && finalUrl == null) {
                        val res2Json = app.post(apiPost2, headers = headers, json = mapOf("id" to downloadId, "method" to method, "captchaValue" to ""), interceptor = interceptor).parsedSafe<Map<String, Any?>>()
                        val data2Node = res2Json?.get("data")
                        if (data2Node is String) {
                            finalUrl = data2Node
                        } else if (data2Node is List<*>) {
                            finalUrl = data2Node.firstOrNull()?.toString()
                        }
                    }
                    
                    if (finalUrl != null && finalUrl.startsWith("http")) {
                        callback.invoke(newExtractorLink("FilePress", "FilePress Fast", finalUrl, ExtractorLinkType.VIDEO) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        })
                        extracted = true
                    }
                } catch (e: Exception) { continue }
            }

            // WebViewResolver Fallback if API fails
            if (!extracted) {
                try {
                    val doc = app.get(url, interceptor = WebViewResolver(Regex("worker|r2\.dev|cloudflare|drive"))).document
                    doc.select("a").mapNotNull { it.attr("abs:href") }.filter { it.startsWith("http") }.forEach { link ->
                        if (link.contains("worker") || link.contains("r2.dev")) {
                            callback.invoke(newExtractorLink(name, "FilePress Web", link, ExtractorLinkType.VIDEO) { quality = Qualities.Unknown.value })
                        }
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}

class GDFlix : ExtractorApi() {
    override var name = "GDFlix"
    override var mainUrl = "https://gdflix.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val interceptor = CloudflareKiller()
            val host = java.net.URI(url).host
            val headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to url)
            
            var foundDirect = false
            
            // Native Direct Bypass attempt
            try {
                val response = app.get(url, headers = headers, interceptor = interceptor)
                val key = Regex(""""key",\s*"(.*?)"""").find(response.text)?.groupValues?.get(1)
                if (key != null) {
                    val reqBody = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM).addFormDataPart("action", "direct").addFormDataPart("key", key).addFormDataPart("action_token", "").build()
                    val postRes = app.post(url, headers = mapOf("x-token" to host, "Origin" to "https://$host"), requestBody = reqBody, cookies = response.cookies, interceptor = interceptor).parsedSafe<Map<String, String>>()
                    val nextUrl = postRes?.get("url")
                    if (nextUrl != null) {
                        val workerRes = app.get(nextUrl, headers = headers, cookies = response.cookies, interceptor = interceptor)
                        var finalUrl = Regex("""let worker_url\s*=\s*['"](.*?)['"];""").find(workerRes.text)?.groupValues?.get(1)
                        if (finalUrl != null) {
                            if (finalUrl.endsWith(".zip", true)) finalUrl = finalUrl.removeSuffix(".zip").removeSuffix(".ZIP")
                            callback.invoke(newExtractorLink("GDFlix", "GDFlix Direct", finalUrl, ExtractorLinkType.VIDEO) { this.quality = Qualities.Unknown.value })
                            foundDirect = true
                        }
                    }
                }
            } catch (e: Exception) {}

            // WebViewResolver Powerful Fallback
            if (!foundDirect) {
                try {
                    val doc = app.get(url, interceptor = WebViewResolver(Regex("worker|r2\.dev|cloudflare|drive|gofile|pixeldrain"))).document
                    val validLinks = doc.select("a").mapNotNull { it.attr("abs:href") }.filter { it.startsWith("http") }
                    validLinks.forEach { link ->
                        if (link.contains("worker") || link.contains("r2.dev") || link.contains("fastcdn")) {
                            var decoded = link
                            if (link.contains("url=")) {
                                try { decoded = java.net.URLDecoder.decode(link.substringAfter("url="), "UTF-8") } catch (e: Exception) {}
                            }
                            if (decoded.endsWith(".zip", true)) decoded = decoded.removeSuffix(".zip").removeSuffix(".ZIP")
                            callback.invoke(newExtractorLink("GDFlix", "GDFlix Web", decoded, ExtractorLinkType.VIDEO) { quality = Qualities.Unknown.value })
                        } else if (!link.contains("gdflix", true)) {
                            loadExtractor(link, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        } catch (e: Exception) { e.printStackTrace() }
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
        val interceptor = CloudflareKiller()
        val headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to url)
        
        suspend fun resolve(targetUrl: String, depth: Int = 0) {
            if (depth > 2) return
            try {
                // Try WebViewResolver if Cloudflare blocks
                var doc = app.get(targetUrl, interceptor = interceptor, headers = headers).document
                if (doc.title().contains("Just a moment", true) || doc.title().contains("Cloudflare")) {
                    doc = app.get(targetUrl, interceptor = WebViewResolver(Regex("r2\.dev|cloudflare|worker|drive|gofile"))).document
                }

                val validLinks = doc.select("a").mapNotNull { it.attr("abs:href") }.filter { it.startsWith("http") }

                val unpacked = doc.select("script").mapNotNull { it.data() }.firstOrNull { it.contains("eval(function(p,a,c,k,e,d)") }
                if (unpacked != null) {
                    val decoded = JsUnpacker(unpacked).unpack()
                    Regex("https?://[^"']+").findAll(decoded ?: "").map { it.value }.forEach { link ->
                        if (link.contains("r2.dev") || link.contains("worker")) {
                            callback.invoke(newExtractorLink(name, "HubCloud Packed", link, ExtractorLinkType.VIDEO) { quality = Qualities.Unknown.value })
                        }
                    }
                }

                for (link in validLinks) {
                    if (link.contains("hubcloud.cx") || link.contains("r2.dev") || link.contains("cloudflare") || link.contains("worker")) {
                        callback.invoke(newExtractorLink(this.name, "HubCloud Direct", link, ExtractorLinkType.VIDEO) { quality = Qualities.Unknown.value })
                    } else if (link.contains("hubcloud", true) && !link.contains("cx") && link != targetUrl) {
                        resolve(link, depth + 1)
                    } else if (!link.contains("hubcloud", true)) {
                        loadExtractor(link, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {}
        }
        resolve(url)
    }
}


class GoflixSbs : ExtractorApi() {
    override var name = "GoflixSbs"
    override var mainUrl = "https://goflix.sbs"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val interceptor = CloudflareKiller()
            val res = app.get(url, interceptor = interceptor).document
            val validLinks = res.select("a").mapNotNull { it.attr("abs:href") }
                .filter { 
                    it.contains("gofile") || 
                    it.contains("1fichier") || 
                    it.contains("megaup") || 
                    it.contains("pixeldrain") ||
                    it.contains("filemoon") ||
                    it.contains("streamwish")
                }
            
            validLinks.forEach { link ->
                loadExtractor(link.replace("&amp;", "&"), subtitleCallback, callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
