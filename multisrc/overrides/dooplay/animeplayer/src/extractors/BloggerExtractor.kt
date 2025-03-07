package eu.kanade.tachiyomi.animeextension.pt.animeplayer.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class BloggerExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        return client.newCall(GET(url)).execute()
            .use { it.body.string() }
            .substringAfter("\"streams\":[")
            .substringBefore("]")
            .split("},")
            .map {
                val url = it.substringAfter("{\"play_url\":\"").substringBefore('"')
                val format = it.substringAfter("\"format_id\":").substringBefore("}")
                val quality = when (format) {
                    "18" -> "360p"
                    "22" -> "720p"
                    else -> "Unknown"
                }
                Video(url, quality, url, headers = headers)
            }
    }
}
