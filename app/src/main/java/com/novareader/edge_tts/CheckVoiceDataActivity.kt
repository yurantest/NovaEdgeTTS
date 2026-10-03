package com.novareader.edge_tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.ArrayList
import java.util.Locale

/**
 * Required by Android Settings and many apps.
 * Returns the list of available voices so the engine appears in
 * "Text-to-speech output → Preferred engine" and language lists.
 */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repo = EdgeVoiceRepository(this)
        val selected = repo.selected()
        val all = repo.loadCached()

        // Prefer only user-selected voices; if none selected yet, expose all cached
        val voices = if (selected.isNotEmpty()) {
            all.filter { selected.contains(it.name) }
        } else {
            all
        }

        val available = ArrayList<String>()
        for (v in voices) {
            // Format expected by Settings: ISO3lang-ISO3country  e.g. "rus-RUS", "eng-USA"
            val locale = Locale.forLanguageTag(v.locale.replace('_', '-'))
            val iso3Lang = try { locale.isO3Language } catch (_: Exception) { continue }
            val iso3Country = try {
                if (locale.country.isNotEmpty()) locale.isO3Country else ""
            } catch (_: Exception) { "" }

            val key = if (iso3Country.isNotEmpty()) "$iso3Lang-$iso3Country" else iso3Lang
            if (key !in available) available.add(key)
        }

        val result = Intent()
        result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
        result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList())
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        finish()
    }
}
