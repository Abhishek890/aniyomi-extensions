package eu.kanade.tachiyomi.animeextension.en.multimovies

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.animeextension.en.multimovies.extractors.AutoEmbedExtractor
import eu.kanade.tachiyomi.animeextension.en.multimovies.extractors.MultimoviesCloudExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Multimovies : DooPlay(
    "en",
    "Multimovies",
    "https://multimovies.shop",
) {
    override val client = network.cloudflareClient

    private val defaultBaseUrl = "https://multimovies.shop"

    override val baseUrl by lazy { getPrefBaseUrl() }

    override val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/genre/anime-series/page/$page/")

    override fun popularAnimeSelector() = latestUpdatesSelector()

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // ============================== Episodes ==============================

    override val seasonListSelector = "div#seasons > div:not(:contains(no episodes this season))"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.use { getRealAnimeDoc(it.asJsoup()) }
        val seasonList = doc.select(seasonListSelector)
        return if ("/movies/" in doc.location()) {
            SEpisode.create().apply {
                setUrlWithoutDomain(doc.location())
                episode_number = 1F
                name = episodeMovieText
            }.let(::listOf)
        } else if (seasonList.size < 1) {
            throw Exception("The source provides ZERO episodes.")
        } else {
            seasonList.flatMap(::getSeasonEpisodes).reversed()
        }
    }

    // ============================ Video Links =============================

    override val prefQualityValues = arrayOf("1080p", "720p", "480p", "360p", "240p")
    override val prefQualityEntries = arrayOf("1080", "720", "480", "360", "240")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.flatMap(::getPlayerVideos)
    }

    private fun getPlayerVideos(player: Element): List<Video> {
        if (player.attr("data-nume") == "trailer") return emptyList()
        val url = getPlayerUrl(player)
        val streamSbServers = listOf(
            "sbembed.com", "sbembed1.com", "sbplay.org",
            "sbvideo.net", "streamsb.net", "sbplay.one",
            "cloudemb.com", "playersb.com", "tubesb.com",
            "sbplay1.com", "embedsb.com", "watchsb.com",
            "sbplay2.com", "japopav.tv", "viewsb.com",
            "sbfast", "sbfull.com", "javplaya.com",
            "ssbstream.net", "p1ayerjavseen.com", "sbthe.com",
            "sbchill.com", "sblongvu.com", "sbanh.com",
            "sblanh.com", "sbhight.com", "sbbrisk.com",
            "sbspeed.com", "multimovies.website",
        )
        return when {
            streamSbServers.any { it in url } ->
                StreamSBExtractor(client).videosFromUrl(url, headers = headers, prefix = "[multimovies]")

            url.contains(baseUrl.toHttpUrl().host, true) ->
                MultimoviesCloudExtractor(client).videosFromUrl(url)
            url.contains("autoembed.to") || url.contains("2embed.to") -> {
                val newHeaders = headers.newBuilder()
                    .set("Referer", url)
                    .build()
                AutoEmbedExtractor(client).videosFromUrl(url, headers = newHeaders)
            }
            else -> emptyList()
        }
    }

    private fun getPlayerUrl(player: Element): String {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()

        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .execute()
            .use { response ->
                response.body.string()
                    .substringAfter("\"embed_url\":\"")
                    .substringBefore("\",")
                    .replace("\\", "")
                    .let { url ->
                        when {
                            url.lowercase().contains("iframe") -> {
                                url.substringAfter("=\"")
                                    .substringBefore("\" ")
                            }
                            else -> url
                        }
                    }
            }
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page/?s=$query", headers)
        } else {
            val filterList = if (filters.isEmpty()) getFilterList() else filters
            val genreFilter = filterList.getFirst<GenreFilter>()
            val streamingFilter = filterList.getFirst<StreamingFilter>()
            val ficUniFilter = filterList.getFirst<FicUniFilter>()
            val channelFilter = filterList.getFirst<ChannelFilter>()

            when {
                genreFilter.state != 0 -> GET("$baseUrl/genre/$genreFilter/page/$page", headers)
                streamingFilter.state != 0 -> GET("$baseUrl/genre/$streamingFilter/page/$page", headers)
                ficUniFilter.state != 0 -> GET("$baseUrl/genre/$ficUniFilter/page/$page", headers)
                channelFilter.state != 0 -> GET("$baseUrl/genre/$channelFilter/page/$page", headers)
                else -> popularAnimeRequest(page)
            }
        }
    }

    // ============================== Filters ===============================
	
    override fun getFilterList() = getMultimoviesFilterList()
    override val fetchGenres = false

    // =============================== Latest ===============================
	
    override fun latestUpdatesNextPageSelector() = "div.pagination > *:last-child:not(span):not(.current)"

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            this.setDefaultValue(defaultBaseUrl)
            dialogTitle = BASE_URL_PREF_TITLE

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_ANIYOMI, Toast.LENGTH_LONG).show()
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = PREF_SERVER_TITLE
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        super.setupPreferenceScreen(screen) // quality pref
    }

    // ============================= Utilities ==============================

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return first { it is R } as R
    }

    companion object {
        private const val RESTART_ANIYOMI = "Restart Aniyomi to apply new setting."
        private const val BASE_URL_PREF_TITLE = "Override BaseUrl"
        private val BASE_URL_PREF = "overrideBaseUrl_v${AppInfo.getVersionName()}"
        private const val BASE_URL_PREF_SUMMARY = "For temporary uses. Updating the extension will erase this setting."

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Preferred Server"
        private const val PREF_SERVER_DEFAULT = "multimovies"
        private val PREF_SERVER_ENTRIES = arrayOf(
            "multimovies",
            "2Embed Vidcloud",
            "2Embed Voe",
            "2Embed Streamlare",
            "2Embed MixDrop",
            "Gomo Dood",
        )
        private val PREF_SERVER_VALUES = arrayOf(
            "multimovies",
            "[2embed] server vidcloud",
            "[2embed] server voe",
            "[2embed] server streamlare",
            "[2embed] server mixdrop",
            "[gomostream] dood",
        )
    }
}
