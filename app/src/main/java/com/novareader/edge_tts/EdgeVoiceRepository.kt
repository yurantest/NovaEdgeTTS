package com.novareader.edge_tts

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

class EdgeVoiceRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("edge_voices", Context.MODE_PRIVATE)
    private val cacheKey = "voices_json"
    private val selectedKey = "selected"
    private val endpoint = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list?trustedclienttoken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    fun loadCached(): List<EdgeVoice> = parse(prefs.getString(cacheKey, "[]") ?: "[]")

    fun selected(): Set<String> = prefs.getStringSet(selectedKey, null)?.toSet() ?: emptySet()

    fun setSelected(name: String, enabled: Boolean) {
        val s = selected().toMutableSet()
        if (enabled) s.add(name) else s.remove(name)
        prefs.edit().putStringSet(selectedKey, s).apply()
    }

    fun replaceSelected(names: Set<String>) = prefs.edit().putStringSet(selectedKey, names).apply()

    fun refresh(): List<EdgeVoice> {
        val c = URL(endpoint).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/143 Safari/537.36 Edg/143")
        c.setRequestProperty("Accept", "*/*")
        try {
            if (c.responseCode !in 200..299) error("Voice list HTTP ${c.responseCode}")
            val json = c.inputStream.bufferedReader().use { it.readText() }
            prefs.edit().putString(cacheKey, json).apply()
            return parse(json)
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
