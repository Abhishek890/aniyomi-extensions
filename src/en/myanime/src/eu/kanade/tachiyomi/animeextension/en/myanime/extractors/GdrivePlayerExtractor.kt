package eu.kanade.tachiyomi.animeextension.en.myanime.extractors

import android.util.Base64
import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.security.DigestException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class GdrivePlayerExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, name: String): List<Video> {
        val headers = Headers.headersOf(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Host",
            "gdriveplayer.to",
            "Referer",
            "https://myanime.live/",
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
        )
        val body = client.newCall(GET(url.replace(".us", ".to"), headers = headers)).execute()
            .body.string()
        val subtitleUrl = Jsoup.parse(body).selectFirst("div:contains(\\.srt)")
        val subtitleList = mutableListOf<Track>()
        if (subtitleUrl != null) {
            try {
                subtitleList.add(
                    Track(
                        "https://gdriveplayer.to/?subtitle=" + subtitleUrl.text(),
                        "Subtitles",
                    ),
                )
            } catch (a: Exception) { }
        }

        val eval = JsUnpacker.unpackAndCombine(body)!!.replace("\\", "")
        val json = Json.decodeFromString<JsonObject>(REGEX_DATAJSON.getFirst(eval))
        val sojson = REGEX_SOJSON.getFirst(eval)
            .split(Regex("\\D+"))
            .joinToString("") {
                Char(it.toInt()).toString()
            }
        val password = REGEX_PASSWORD.getFirst(sojson).toByteArray()
        val decrypted = decryptAES(password, json)!!
        val secondEval = JsUnpacker.unpackAndCombine(decrypted)!!.replace("\\", "")
        return REGEX_VIDEOURL.findAll(secondEval)
            .distinctBy { it.groupValues[2] } // remove duplicates by quality
            .map {
                val qualityStr = it.groupValues[2]
                val quality = "$PLAYER_NAME ${qualityStr}p - $name"
                val videoUrl = "https:" + it.groupValues[1] + "&res=$qualityStr"
                try {
                    Video(videoUrl, quality, videoUrl, subtitleTracks = subtitleList)
                } catch (a: Exception) {
                    Video(videoUrl, quality, videoUrl)
                }
            }.toList()
    }

    private fun decryptAES(password: ByteArray, json: JsonObject): String? {
        val salt = json["s"]!!.jsonPrimitive.content
        val encodedCiphetext = json["ct"]!!.jsonPrimitive.content
        val ciphertext = Base64.decode(encodedCiphetext, Base64.DEFAULT)
        val (key, iv) = generateKeyAndIv(password, salt.decodeHex())
            ?: return null
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decryptedData = String(cipher.doFinal(ciphertext))
        return decryptedData
    }

    // https://stackoverflow.com/a/41434590/8166854
    private fun generateKeyAndIv(
        password: ByteArray,
        salt: ByteArray,
        hashAlgorithm: String = "MD5",
        keyLength: Int = 32,
        ivLength: Int = 16,
        iterations: Int = 1,
    ): List<ByteArray>? {
        val md = MessageDigest.getInstance(hashAlgorithm)
        val digestLength = md.getDigestLength()
        val targetKeySize = keyLength + ivLength
        val requiredLength = (targetKeySize + digestLength - 1) / digestLength * digestLength
        var generatedData = ByteArray(requiredLength)
        var generatedLength = 0

        try {
            md.reset()

            while (generatedLength < targetKeySize) {
                if (generatedLength > 0) {
                    md.update(
                        generatedData,
                        generatedLength - digestLength,
                        digestLength,
                    )
                }

                md.update(password)
                md.update(salt, 0, 8)
                md.digest(generatedData, generatedLength, digestLength)

                for (i in 1 until iterations) {
                    md.update(generatedData, generatedLength, digestLength)
                    md.digest(generatedData, generatedLength, digestLength)
                }

                generatedLength += digestLength
            }
            val result = listOf(
                generatedData.copyOfRange(0, keyLength),
                generatedData.copyOfRange(keyLength, targetKeySize),
            )
            return result
        } catch (e: DigestException) {
            return null
        }
    }

    private fun Regex.getFirst(item: String): String {
        return find(item)?.groups?.elementAt(1)?.value!!
    }

    // Stolen from AnimixPlay(EN) / GogoCdnExtractor
    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    companion object {
        private const val PLAYER_NAME = "GDRIVE"

        private val REGEX_DATAJSON = Regex("data='(\\S+?)'")
        private val REGEX_PASSWORD = Regex("var pass = \"(\\S+?)\"")
        private val REGEX_SOJSON = Regex("null,['|\"](\\w+)['|\"]")
        private val REGEX_VIDEOURL = Regex("file\":\"(\\S+?)\".*?res=(\\d+)")
    }
}
