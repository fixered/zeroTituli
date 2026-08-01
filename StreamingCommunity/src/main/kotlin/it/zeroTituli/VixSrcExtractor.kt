package it.zeroTituli

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/**
 * Secondo player, indipendente dal sito: prende il titolo dall'id TMDb. Utile quando il player
 * interno non risponde. Ripreso dal plugin di doGior.
 */
class VixSrcExtractor : ExtractorApi() {
    override val mainUrl = "vixsrc.to"
    override val name = "VixSrc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val playlistUrl = playlistUrl(url, referer ?: "https://vixsrc.to/") ?: return
        callback(
            newExtractorLink(
                source = "VixSrc",
                name = "StreamingCommunity - VixSrc",
                url = playlistUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer ?: "https://vixsrc.to/"
            }
        )
    }

    private suspend fun playlistUrl(url: String, referer: String): String? {
        val script = script(url, referer) ?: return null
        val master = script.optJSONObject("masterPlaylist") ?: return null
        val params = master.optJSONObject("params")
        val token = params?.optString("token").orEmpty()
        val expires = params?.optString("expires").orEmpty()
        val playlist = master.optString("url").takeIf { it.isNotBlank() } ?: return null

        val query = "token=$token&expires=$expires"
        var result = if ("?b" in playlist) {
            "${playlist.replace("?b:1", "?b=1")}&$query"
        } else {
            "$playlist?$query"
        }
        if (script.optBoolean("canPlayFHD")) result += "&h=1"
        return result
    }

    private suspend fun script(url: String, referer: String): JSONObject? = runCatching {
        val host = url.toHttpUrl().host
        val headers = mapOf(
            "Accept" to "*/*",
            "Alt-Used" to host,
            "Connection" to "keep-alive",
            "Referer" to referer,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/133.0",
        )
        val page = app.get(url, headers = headers).document
        val script = page.select("script")
            .find { it.data().contains("masterPlaylist") }
            ?.data()?.replace("\n", "\t")
            ?: return null
        JSONObject(VixScript.toJson(script))
    }.getOrNull()
}
