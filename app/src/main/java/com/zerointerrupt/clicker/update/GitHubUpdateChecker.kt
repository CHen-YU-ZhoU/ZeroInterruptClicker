package com.zerointerrupt.clicker.update

import android.content.Context
import android.text.Html
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

data class GitHubReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseName: String,
    val body: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?,
    val apkFileName: String?,
    val viaMirror: Boolean = false,
    val mirrorLabel: String? = null
)

object GitHubUpdateChecker {
    private const val REPO_OWNER = "CHen-YU-ZhoU"
    private const val REPO_NAME = "ZeroInterruptClicker"
    private const val REPO_PATH = "$REPO_OWNER/$REPO_NAME"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO_PATH/releases/latest"
    const val RELEASES_PAGE_URL = "https://github.com/$REPO_PATH/releases"
    private const val RELEASES_ATOM_URL = "https://github.com/$REPO_PATH/releases.atom"
    private const val LATEST_RELEASE_WEB_URL = "https://github.com/$REPO_PATH/releases/latest"
    private const val EXPANDED_ASSETS_URL_PREFIX = "https://github.com/$REPO_PATH/releases/expanded_assets/"

    private const val DIRECT_CONNECT_TIMEOUT_MS = 4_500
    private const val DIRECT_READ_TIMEOUT_MS = 5_000
    private const val MIRROR_CONNECT_TIMEOUT_MS = 4_500
    private const val MIRROR_READ_TIMEOUT_MS = 5_000

    private val MIRROR_PREFIXES = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://gh.llkk.cc/",
        "https://mirror.ghproxy.com/"
    )

    private const val PREF_NAME = "clicker_update_prefs"
    private const val KEY_IGNORED_TAG = "ignored_tag"

    private data class HttpTextResponse(
        val code: Int,
        val body: String,
        val finalUrl: String
    )

    private data class AtomEntryData(
        val tagName: String,
        val title: String,
        val body: String,
        val htmlUrl: String
    )

    fun getIgnoredTag(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_IGNORED_TAG, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun setIgnoredTag(context: Context, tagName: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_IGNORED_TAG, tagName.trim())
            .apply()
    }

    /**
     * Multi-tier silent fallback update checker:
     * 1. Official GitHub REST API (fastest and richest when unthrottled)
     * 2. Direct GitHub Web / Atom Feed (bypasses 60 req/hr API 403 rate limits)
     * 3. Built-in GitHub mirror prefix pool (works without proxy, and automatically
     *    accelerates the resolved APK download URL with the working mirror prefix)
     */
    suspend fun checkLatestRelease(currentVersion: String): Result<GitHubReleaseInfo?> =
        withContext(Dispatchers.IO) {
            val errors = mutableListOf<String>()

            // Tier 1: Official GitHub REST API
            try {
                return@withContext Result.success(fetchViaOfficialApi(currentVersion))
            } catch (t: Throwable) {
                errors += "API: ${t.message ?: t.javaClass.simpleName}"
            }

            // Tier 2: Direct GitHub Atom/Web (avoids api.github.com HTTP 403 rate limit)
            try {
                return@withContext Result.success(
                    fetchViaWebEndpoint(
                        currentVersion = currentVersion,
                        mirrorPrefix = "",
                        connectTimeoutMs = DIRECT_CONNECT_TIMEOUT_MS,
                        readTimeoutMs = DIRECT_READ_TIMEOUT_MS
                    )
                )
            } catch (t: Throwable) {
                errors += "DirectWeb: ${t.message ?: t.javaClass.simpleName}"
            }

            // Tier 3: Mirror prefix pool
            for (prefix in MIRROR_PREFIXES) {
                try {
                    return@withContext Result.success(
                        fetchViaWebEndpoint(
                            currentVersion = currentVersion,
                            mirrorPrefix = prefix,
                            connectTimeoutMs = MIRROR_CONNECT_TIMEOUT_MS,
                            readTimeoutMs = MIRROR_READ_TIMEOUT_MS
                        )
                    )
                } catch (t: Throwable) {
                    val host = extractHostLabel(prefix)
                    errors += "$host: ${t.message ?: t.javaClass.simpleName}"
                }
            }

            val summary = if (errors.isNotEmpty()) {
                "直连与镜像站均不可用，请检查网络后重试"
            } else {
                "网络异常"
            }
            Result.failure(IllegalStateException(summary))
        }

    private fun fetchViaOfficialApi(currentVersion: String): GitHubReleaseInfo? {
        val resp = httpGetText(
            urlStr = LATEST_RELEASE_API,
            accept = "application/vnd.github+json",
            userAgent = "ZeroInterruptClicker/$currentVersion",
            connectTimeoutMs = DIRECT_CONNECT_TIMEOUT_MS,
            readTimeoutMs = DIRECT_READ_TIMEOUT_MS
        )
        if (resp.code == HttpURLConnection.HTTP_NOT_FOUND) {
            return null
        }
        if (resp.code !in 200..299) {
            throw IllegalStateException("HTTP ${resp.code}")
        }
        val json = JSONObject(resp.body)
        val tagName = json.optString("tag_name", "").trim()
        if (tagName.isBlank()) return null

        val remoteVersion = normalizeVersion(tagName)
        if (!isNewerVersion(remoteVersion, currentVersion)) {
            return null
        }

        val releaseName = json.optString("name", tagName).trim().ifBlank { tagName }
        val body = json.optString("body", "").trim()
        val htmlUrl = json.optString("html_url", RELEASES_PAGE_URL).trim().ifBlank { RELEASES_PAGE_URL }

        var apkUrl: String? = null
        var apkName: String? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val dl = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && dl.isNotBlank()) {
                    apkUrl = dl
                    apkName = name
                    break
                }
            }
        }

        return GitHubReleaseInfo(
            tagName = tagName,
            versionName = remoteVersion,
            releaseName = releaseName,
            body = body,
            htmlUrl = htmlUrl,
            apkDownloadUrl = apkUrl,
            apkFileName = apkName,
            viaMirror = false,
            mirrorLabel = null
        )
    }

    private fun fetchViaWebEndpoint(
        currentVersion: String,
        mirrorPrefix: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): GitHubReleaseInfo? {
        val atomEntry = try {
            fetchLatestFromAtom(
                currentVersion = currentVersion,
                mirrorPrefix = mirrorPrefix,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs
            )
        } catch (atomErr: Throwable) {
            fetchLatestFromRedirectPage(
                currentVersion = currentVersion,
                mirrorPrefix = mirrorPrefix,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs
            ) ?: throw atomErr
        } ?: return null

        val tagName = atomEntry.tagName.trim()
        if (tagName.isBlank()) return null

        val remoteVersion = normalizeVersion(tagName)
        if (!isNewerVersion(remoteVersion, currentVersion)) {
            return null
        }

        val (rawApkUrl, apkName) = fetchApkAssetForTag(
            tagName = tagName,
            currentVersion = currentVersion,
            mirrorPrefix = mirrorPrefix,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        )

        val resolvedApkUrl = rawApkUrl?.let { raw ->
            if (mirrorPrefix.isNotEmpty() && !raw.startsWith(mirrorPrefix)) {
                "$mirrorPrefix$raw"
            } else {
                raw
            }
        }

        val isMirror = mirrorPrefix.isNotEmpty()
        return GitHubReleaseInfo(
            tagName = tagName,
            versionName = remoteVersion,
            releaseName = atomEntry.title.ifBlank { tagName },
            body = atomEntry.body,
            htmlUrl = atomEntry.htmlUrl.ifBlank { "https://github.com/$REPO_PATH/releases/tag/$tagName" },
            apkDownloadUrl = resolvedApkUrl,
            apkFileName = apkName,
            viaMirror = isMirror,
            mirrorLabel = if (isMirror) extractHostLabel(mirrorPrefix) else null
        )
    }

    private fun fetchLatestFromAtom(
        currentVersion: String,
        mirrorPrefix: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): AtomEntryData? {
        val url = "$mirrorPrefix$RELEASES_ATOM_URL"
        val resp = httpGetText(
            urlStr = url,
            accept = "application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.8",
            userAgent = "ZeroInterruptClicker/$currentVersion",
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        )
        if (resp.code == HttpURLConnection.HTTP_NOT_FOUND) {
            return null
        }
        if (resp.code !in 200..299) {
            throw IllegalStateException("Atom HTTP ${resp.code}")
        }
        val xml = resp.body
        if (!xml.contains("<feed", ignoreCase = true)) {
            throw IllegalStateException("非有效 Atom 响应")
        }
        return parseFirstAtomEntry(xml)
    }

    private fun parseFirstAtomEntry(xml: String): AtomEntryData? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        var insideEntry = false
        var hasFeed = false
        var entryId = ""
        var entryTitle = ""
        var entryContent = ""
        var entryHref = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.orEmpty().lowercase()
                    if (name == "feed") {
                        hasFeed = true
                    } else if (name == "entry") {
                        insideEntry = true
                    } else if (insideEntry) {
                        when (name) {
                            "id" -> entryId = parser.nextText().orEmpty().trim()
                            "title" -> entryTitle = parser.nextText().orEmpty().trim()
                            "content" -> entryContent = parser.nextText().orEmpty()
                            "link" -> {
                                val href = parser.getAttributeValue(null, "href").orEmpty().trim()
                                if (href.contains("/releases/tag/")) {
                                    entryHref = href
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (insideEntry && parser.name.equals("entry", ignoreCase = true)) {
                        val tag = extractTagFromHrefOrId(entryHref, entryId)
                        if (tag.isNotBlank()) {
                            val cleanBody = htmlToPlainText(entryContent)
                            val canonicalHtmlUrl = "https://github.com/$REPO_PATH/releases/tag/$tag"
                            return AtomEntryData(
                                tagName = tag,
                                title = entryTitle.ifBlank { tag },
                                body = cleanBody,
                                htmlUrl = canonicalHtmlUrl
                            )
                        }
                        break
                    }
                }
            }
            eventType = parser.next()
        }
        if (!hasFeed) {
            throw IllegalStateException("Atom 结构无效")
        }
        return null
    }

    private fun fetchLatestFromRedirectPage(
        currentVersion: String,
        mirrorPrefix: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): AtomEntryData? {
        val url = "$mirrorPrefix$LATEST_RELEASE_WEB_URL"
        val resp = httpGetText(
            urlStr = url,
            accept = "text/html,application/xhtml+xml,*/*;q=0.8",
            userAgent = "ZeroInterruptClicker/$currentVersion",
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        )
        if (resp.code == HttpURLConnection.HTTP_NOT_FOUND) {
            return null
        }
        if (resp.code !in 200..299) {
            throw IllegalStateException("ReleasePage HTTP ${resp.code}")
        }
        val tagFromFinalUrl = extractTagFromUrl(resp.finalUrl)
        val tagFromHtml = if (tagFromFinalUrl.isBlank()) {
            val regex = Regex("""/$REPO_OWNER/$REPO_NAME/releases/tag/([^"'/?#<>\s]+)""", RegexOption.IGNORE_CASE)
            regex.find(resp.body)?.groupValues?.getOrNull(1)?.let { decodeUrlSegment(it) }.orEmpty()
        } else {
            tagFromFinalUrl
        }
        if (tagFromHtml.isBlank()) {
            throw IllegalStateException("未能解析最新版本标签")
        }
        return AtomEntryData(
            tagName = tagFromHtml,
            title = tagFromHtml,
            body = "",
            htmlUrl = "https://github.com/$REPO_PATH/releases/tag/$tagFromHtml"
        )
    }

    private fun fetchApkAssetForTag(
        tagName: String,
        currentVersion: String,
        mirrorPrefix: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): Pair<String?, String?> {
        return try {
            val encodedTag = URLEncoder.encode(tagName, "UTF-8").replace("+", "%20")
            val url = "$mirrorPrefix$EXPANDED_ASSETS_URL_PREFIX$encodedTag"
            val resp = httpGetText(
                urlStr = url,
                accept = "text/html,*/*;q=0.8",
                userAgent = "ZeroInterruptClicker/$currentVersion",
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs
            )
            if (resp.code !in 200..299) return null to null
            val apkRegex = Regex(
                """href="(/$REPO_OWNER/$REPO_NAME/releases/download/[^"]+\.apk)"""",
                RegexOption.IGNORE_CASE
            )
            val relPath = apkRegex.find(resp.body)?.groupValues?.getOrNull(1) ?: return null to null
            val fileName = decodeUrlSegment(relPath.substringAfterLast('/'))
            "https://github.com$relPath" to fileName.ifBlank { null }
        } catch (_: Throwable) {
            null to null
        }
    }

    private fun httpGetText(
        urlStr: String,
        accept: String,
        userAgent: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpTextResponse {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", userAgent)
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val finalUrl = conn.url?.toString().orEmpty()
            HttpTextResponse(code = code, body = body, finalUrl = finalUrl)
        } finally {
            conn.disconnect()
        }
    }

    private fun extractTagFromHrefOrId(href: String, id: String): String {
        val fromHref = extractTagFromUrl(href)
        if (fromHref.isNotBlank()) return fromHref
        val idx = id.lastIndexOf('/')
        return if (idx in 0 until id.lastIndex) {
            decodeUrlSegment(id.substring(idx + 1).trim())
        } else {
            ""
        }
    }

    private fun extractTagFromUrl(url: String): String {
        val marker = "/releases/tag/"
        val idx = url.indexOf(marker, ignoreCase = true)
        if (idx < 0) return ""
        val tail = url.substring(idx + marker.length)
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore('/')
            .trim()
        return decodeUrlSegment(tail)
    }

    private fun decodeUrlSegment(raw: String): String {
        return try {
            URLDecoder.decode(raw, "UTF-8").trim()
        } catch (_: Throwable) {
            raw.trim()
        }
    }

    private fun htmlToPlainText(rawHtml: String): String {
        if (rawHtml.isBlank()) return ""
        val plain = try {
            Html.fromHtml(rawHtml, Html.FROM_HTML_MODE_COMPACT).toString()
        } catch (_: Throwable) {
            rawHtml.replace(Regex("<[^>]+>"), "")
        }
        return plain
            .replace("\r\n", "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun extractHostLabel(prefix: String): String {
        return try {
            URL(prefix).host.ifBlank { prefix }
        } catch (_: Throwable) {
            prefix
        }
    }

    internal fun normalizeVersion(raw: String): String {
        return raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .trim()
    }

    internal fun isNewerVersion(remoteRaw: String, currentRaw: String): Boolean {
        val remoteParts = normalizeVersion(remoteRaw).split('.').map { it.toIntOrNull() ?: 0 }
        val currentParts = normalizeVersion(currentRaw).split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }
}
