package eu.kanade.tachiyomi.animeextension.pt.megaflix.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class MixDropExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, lang: String = ""): Video? {
        val doc = client.newCall(GET(url)).execute().asJsoup()
        val unpacked = doc.selectFirst("script:containsData(eval):containsData(MDCore)")
            ?.data()
            ?.let(JsUnpacker::unpackAndCombine)
            ?: return null

        val videoUrl = "https:" + unpacked.substringAfter("Core.wurl=\"")
            .substringBefore("\"")

        val quality = ("MixDrop").let {
            when {
                lang.isNotBlank() -> "$it($lang)"
                else -> it
            }
        }
        return Video(videoUrl, quality, videoUrl)
    }
}
