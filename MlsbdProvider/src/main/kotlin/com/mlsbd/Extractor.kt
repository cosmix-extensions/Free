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
            val apiGet = "https://$host/api/file/get/$fileId"
            val apiPost = "https://$host/api/file/downlaod/"
            val apiPost2 = "https://$host/api/file/downlaod2/"
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "application/json, text/plain, */*",
                "Origin" to "https://$host",
                "Referer" to url
            )

            // Step 1: Initialize to get options
            app.get(apiGet, headers = headers, interceptor = interceptor)
            
            // Step 2: Try CloudR2Download and other methods
            val methodsToTry = listOf("cloudR2Downlaod", "cloudDownlaod", "indexDownlaod", "TelegramDirectDownlaod", "gpDirectDownlaod", "dotFlixDownlaod")
            
            var extracted = false
            for (method in methodsToTry) {
                if (extracted) break
                try {
                    val initialPost = app.post(apiPost, headers = headers, json = mapOf("id" to fileId, "method" to method), interceptor = interceptor).parsedSafe<Map<String, Any>>()
                    
                    var data1 = initialPost?.get("data") as? Map<String, Any>
                    var status = data1?.get("status")?.toString() ?: continue
                    var downloadId = data1?.get("downloadId")?.toString()
                    
                    // Polling loop
                    var attempts = 0
                    while (status != "completed" && status != "failed" && attempts < 15) {
                        delay(3000)
                        val poll = app.post(apiPost, headers = headers, json = mapOf("id" to fileId, "method" to method), interceptor = interceptor).parsedSafe<Map<String, Any>>()
                        data1 = poll?.get("data") as? Map<String, Any>
                        status = data1?.get("status")?.toString() ?: "failed"
                        downloadId = data1?.get("downloadId")?.toString()
                        attempts++
                    }

                    if (status == "completed" && downloadId != null) {
                        val res2 = app.post(apiPost2, headers = headers, json = mapOf("id" to downloadId, "method" to method), interceptor = interceptor).parsedSafe<Map<String, Any>>()
                        val finalUrl = res2?.get("data")?.toString()
                        
                        if (finalUrl != null && finalUrl.startsWith("http")) {
                            callback.invoke(
                                newExtractorLink(
                                    "FilePress",
                                    "FilePress $method",
                                    finalUrl,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = url
                                    this.quality = Qualities.Unknown.value
                                    this.headers = headers
                                }
                            )
                            extracted = true
                        }
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to url
            )
            
            val response = app.get(url, headers = headers, interceptor = interceptor)
            val keyRegex = Regex(""""key",\s*"(.*?)"""")
            val key = keyRegex.find(response.text)?.groupValues?.get(1)

            var foundDirect = false

            // Try Direct Bypass first
            if (key != null) {
                val reqBody = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("action", "direct")
                    .addFormDataPart("key", key)
                    .addFormDataPart("action_token", "")
                    .build()

                try {
                    val postRes = app.post(
                        url,
                        headers = mapOf(
                            "x-token" to host,
                            "User-Agent" to headers["User-Agent"]!!,
                            "Origin" to "https://$host",
                            "Referer" to url
                        ),
                        requestBody = reqBody,
                        cookies = response.cookies,
                        interceptor = interceptor
                    ).parsedSafe<Map<String, String>>()

                    val nextUrl = postRes?.get("url")
                    if (nextUrl != null) {
                        val workerRes = app.get(nextUrl, headers = headers, cookies = response.cookies, interceptor = interceptor)
                        val workerUrlRegex = Regex("""let worker_url\s*=\s*['"](.*?)['"];""")
                        var finalUrl = workerUrlRegex.find(workerRes.text)?.groupValues?.get(1)

                        if (finalUrl != null && finalUrl.startsWith("http")) {
                            if (finalUrl.endsWith(".zip", true)) {
                                finalUrl = finalUrl.removeSuffix(".zip").removeSuffix(".ZIP")
                            }
                            
                            foundDirect = true
                            callback.invoke(
                                newExtractorLink(
                                    "GDFlix Fast Cloud",
                                    "GDFlix Fast Cloud",
                                    finalUrl,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = url
                                    this.quality = Qualities.Unknown.value
                                    this.headers = headers
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Ignore exceptions during direct bypass
                }
            }
            
            // Fallback to iterating links
            if (!foundDirect) {
                val validLinks = response.document.select("a").mapNotNull { it.attr("abs:href") }.filter { it.startsWith("http") }
                validLinks.forEach { link ->
                    if (link.contains("busycdn") || link.contains("instant") || link.contains("fastcdn")) {
                        try {
                            val res = app.get(link, headers = headers, interceptor = interceptor)
                            val resUrl = res.url
                            if (resUrl.contains("url=")) {
                                var decoded = resUrl.substringAfter("url=")
                                try {
                                    decoded = java.net.URLDecoder.decode(decoded, "UTF-8")
                                } catch (e: Exception) {}
                                
                                if (decoded.startsWith("http")) {
                                    callback.invoke(
                                        newExtractorLink(
                                            "GDFlix",
                                            "GDFlix Instant DL",
                                            decoded,
                                            ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = url
                                            this.quality = Qualities.Unknown.value
                                            this.headers = headers
                                        }
                                    )
                                }
                            }
                        } catch (e: Exception) {}
                    } else if (link.contains("zfile")) {
                        try {
                            val workerRes = app.get(link, headers = headers, cookies = response.cookies, interceptor = interceptor)
                            val workerUrlRegex = Regex("""let worker_url = '(.*?)';""")
                            var finalUrl = workerUrlRegex.find(workerRes.text)?.groupValues?.get(1)

                            if (finalUrl != null && finalUrl.startsWith("http")) {
                                if (finalUrl.endsWith(".zip", true)) {
                                    finalUrl = finalUrl.removeSuffix(".zip").removeSuffix(".ZIP")
                                }
                                
                                callback.invoke(
                                    newExtractorLink(
                                        "GDFlix",
                                        "GDFlix ZFile",
                                        finalUrl,
                                        ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = url
                                        this.quality = Qualities.Unknown.value
                                        this.headers = headers
                                    }
                                )
                            }
                        } catch (e: Exception) {}
                    } else if (link.contains("goflix") || link.contains("gofile") || link.contains("1fichier") || link.contains("hubcloud") || link.contains("drive") || link.contains("pixeldrain")) {
                        loadExtractor(link, subtitleCallback, callback)
                    } else if (!link.contains("gdflix", true)) {
                        loadExtractor(link, subtitleCallback, callback)
                    }
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
        val interceptor = CloudflareKiller()
        val headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to url)
        
        suspend fun resolve(targetUrl: String, depth: Int = 0) {
            if (depth > 2) return
            try {
                val doc = app.get(targetUrl, interceptor = interceptor, headers = headers).document
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
