package com.novareader.edge_tts

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

class EdgeVoiceRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("edge_voices", Context.MODE_PRIVATE)
    private val cacheKey = "voices_json"
    private val selectedKey = "selected"
    // Как VOICE_LIST в edge_tts/constants.py; к адресу добавляются Sec-MS-GEC и Sec-MS-GEC-Version (см. refresh).
    private val endpoint = "https://${EdgeGec.BASE_URL}/voices/list?trustedclienttoken=${EdgeGec.TRUSTED_CLIENT_TOKEN}"

    fun loadCached(): List<EdgeVoice> = parse(prefs.getString(cacheKey, "[]") ?: "[]")

    fun selected(): Set<String> = prefs.getStringSet(selectedKey, null)?.toSet() ?: emptySet()

    fun setSelected(name: String, enabled: Boolean) {
        val s = selected().toMutableSet()
        if (enabled) s.add(name) else s.remove(name)
        prefs.edit().putStringSet(selectedKey, s).apply()
    }

    fun replaceSelected(names: Set<String>) = prefs.edit().putStringSet(selectedKey, names).apply()

    fun refresh(): List<EdgeVoice> {
        // 403 обычно значит расхождение часов: подстраиваемся по Date сервера и повторяем один раз
        // (как list_voices() в edge-tts: DRM.handle_client_response_error).
        val json = try {
            fetchList()
        } catch (e: ServerRejected) {
            if (e.status == 403 && EdgeGec.adjustClockFromServerDate(e.dateHeader)) {
                FileLogger.log("EdgeVoices: 403 — поправка часов ${EdgeGec.skewSeconds()} с, повтор")
                fetchList()
            } else throw IllegalStateException("Voice list HTTP ${e.status}")
        }
        prefs.edit().putString(cacheKey, json).apply()
        return parse(json)
    }

    private class ServerRejected(val status: Int, val dateHeader: String?) : Exception("HTTP $status")

    private fun fetchList(): String {
        val url = "$endpoint&Sec-MS-GEC=${EdgeGec.currentToken()}&Sec-MS-GEC-Version=${EdgeGec.SEC_MS_GEC_VERSION}"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        // Заголовки как VOICE_HEADERS + muid в edge-tts. Accept-Encoding не задаём:
        // HttpURLConnection сам просит gzip и сам распаковывает ответ.
        c.setRequestProperty("Authority", "speech.platform.bing.com")
        c.setRequestProperty("Sec-CH-UA", "\" Not;A Brand\";v=\"99\", \"Microsoft Edge\";v=\"${EdgeGec.CHROMIUM_MAJOR_VERSION}\", " +
            "\"Chromium\";v=\"${EdgeGec.CHROMIUM_MAJOR_VERSION}\"")
        c.setRequestProperty("Sec-CH-UA-Mobile", "?0")
        c.setRequestProperty("Accept", "*/*")
        c.setRequestProperty("Sec-Fetch-Site", "none")
        c.setRequestProperty("Sec-Fetch-Mode", "cors")
        c.setRequestProperty("Sec-Fetch-Dest", "empty")
        c.setRequestProperty("User-Agent", EdgeGec.USER_AGENT)
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        c.setRequestProperty("Cookie", "muid=${EdgeGec.muid()};")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw ServerRejected(code, c.getHeaderField("Date"))
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun parse(json: String): List<EdgeVoice> {
        val a = JSONArray(json); val out = ArrayList<EdgeVoice>(a.length())
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val name = o.optString("ShortName")
            val locale = o.optString("Locale")
            if (name.isNotBlank() && locale.isNotBlank()) {
                val gender = o.optString("Gender", "")
                val friendly = name.substringAfterLast('-').removeSuffix("Neural")
                out += EdgeVoice(name, locale, gender, friendly)
            }
        }
        return out.sortedWith(compareBy({ it.locale }, { it.name }))
    }
}
