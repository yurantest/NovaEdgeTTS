package com.novareader.edge_tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.ArrayList
import java.util.Locale

/**
 * Required by Android Settings and many apps.
 * Returns every selected Edge voice as a separate TTS voice.
 *
 * Android expects the old TTS locale format:
 *   lang-COUNTRY-variant
 *
 * The variant is important here: two Edge voices can have the same language
 * and country but must remain separate entries (for example
 * rus-RUS-Svetlana and rus-RUS-Dmitry).
 */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repo = EdgeVoiceRepository(this)
        val selected = repo.selected()
        val all = repo.loadCached()

        // Prefer only user-selected voices; if none selected yet, expose all cached.
        val voices = if (selected.isNotEmpty()) {
            all.filter { selected.contains(it.name) }
        } else {
            all
        }

        val available = ArrayList<String>(voices.size)
        val seen = HashSet<String>()

        for (voice in voices) {
            val parts = voice.locale.replace('_', '-').split('-')
            if (parts.size < 2) continue

            val locale = try {
                Locale(parts[0], parts[1])
            } catch (_: Exception) {
                continue
            }

            val iso3Lang = try {
                locale.isO3Language
            } catch (_: Exception) {
                continue
            }

            val iso3Country = try {
                if (locale.country.isNotEmpty()) locale.isO3Country else ""
            } catch (_: Exception) {
                ""
            }

            // Edge ShortName is e.g. ru-RU-SvetlanaNeural.
            // Android's TTS contract explicitly supports a third "variant"
            // component, so keep the actual Edge voice name here instead of
            // collapsing all voices of one locale into one language entry.
            val variant = voice.name
                .substringAfterLast('-')
                .removeSuffix("Neural")
                .trim()

            val key = buildString {
                append(iso3Lang)
                if (iso3Country.isNotEmpty()) {
                    append('-').append(iso3Country)
                }
                if (variant.isNotEmpty()) {
                    append('-').append(variant)
                }
            }

            if (seen.add(key)) {
                available.add(key)
            }
        }

        FileLogger.log("CheckVoiceData: возвращаем ${available.size} отдельных голосов")

        val result = Intent()
        result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
        result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList())
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        finish()
    }
}
