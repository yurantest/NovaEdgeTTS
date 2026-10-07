package com.novareader.edge_tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val lang = intent.getStringExtra("language") ?: "eng"
        val country = intent.getStringExtra("country") ?: ""

        // Настройки Android передают язык как ISO-2 ("af") или ISO-3 ("afr") — SampleTexts понимает оба.
        val text = SampleTexts.forLanguage(lang) ?: SampleTexts.forLanguage("en")!!

        val result = Intent()
        result.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, text)
        setResult(RESULT_OK, result)
        finish()
    }
}
