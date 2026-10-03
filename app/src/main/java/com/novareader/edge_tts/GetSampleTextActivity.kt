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

        val text = when {
            lang.equals("rus", true) || lang.equals("ru", true) ->
                "Это пример текста для синтеза речи."
            lang.equals("eng", true) || lang.equals("en", true) ->
                "This is a sample of text to speech."
            lang.equals("ukr", true) || lang.equals("uk", true) ->
                "Це приклад тексту для синтезу мовлення."
            lang.equals("deu", true) || lang.equals("de", true) ->
                "Dies ist ein Beispieltext zur Sprachsynthese."
            lang.equals("fra", true) || lang.equals("fr", true) ->
                "Ceci est un exemple de synthèse vocale."
            lang.equals("spa", true) || lang.equals("es", true) ->
                "Este es un ejemplo de texto a voz."
            lang.equals("ita", true) || lang.equals("it", true) ->
                "Questo è un esempio di sintesi vocale."
            lang.equals("por", true) || lang.equals("pt", true) ->
                "Este é um exemplo de texto para fala."
            lang.equals("zho", true) || lang.equals("zh", true) || lang.equals("cmn", true) ->
                "这是语音合成的示例文本。"
            lang.equals("jpn", true) || lang.equals("ja", true) ->
                "これは音声合成のサンプルテキストです。"
            else ->
                "This is a sample of text to speech."
        }

        val result = Intent()
        result.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, text)
        setResult(RESULT_OK, result)
        finish()
    }
}
