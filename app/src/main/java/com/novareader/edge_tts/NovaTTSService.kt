package com.novareader.edge_tts

import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import java.util.Locale
import java.util.MissingResourceException

class NovaTTSService : TextToSpeechService() {

    private val repo: EdgeVoiceRepository by lazy {
        FileLogger.log("Инициализация EdgeVoiceRepository")
        EdgeVoiceRepository(this)
    }
    private val client = EdgeTTSClient()
    private val prefetch = EdgePrefetch(client)
    private var currentLanguage = Locale("en", "US")
    private var currentVoiceName: String? = null

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        FileLogger.log("NovaTTSService создан")
        prefetch.warmup()   // DNS + TLS заранее: первая фраза не платит за холодный старт
    }

    override fun onGetVoices(): MutableList<Voice> {
        FileLogger.log("onGetVoices вызван")
        return try {
            val selected = repo.selected()
            val all = repo.loadCached()
            FileLogger.log("Загружено голосов из кэша: ${all.size}, выбрано: ${selected.size}")

            val list = if (selected.isNotEmpty()) all.filter { it.name in selected } else all
            FileLogger.log("Возвращаем ${list.size} голосов")

            list.map { v ->
                Voice(
                    v.name,
                    localeOf(v.locale),
                    Voice.QUALITY_NORMAL,
                    Voice.LATENCY_NORMAL,
                    true,
                    setOf(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
                )
            }.toMutableList()
        } catch (e: Throwable) {
            FileLogger.error("Ошибка в onGetVoices", e)
            mutableListOf()
        }
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        FileLogger.log("onIsValidVoiceName: $voiceName")
        return try {
            if (voiceName.isNullOrBlank()) {
                FileLogger.log("Голос пустой, возвращаем ERROR")
                return TextToSpeech.ERROR
            }
            val selected = repo.selected()
            val all = repo.loadCached()
            val ok = all.any { it.name == voiceName && (selected.isEmpty() || voiceName in selected) }
            FileLogger.log("Результат проверки: $ok")
            if (ok) TextToSpeech.SUCCESS else TextToSpeech.ERROR
        } catch (e: Throwable) {
            FileLogger.error("Ошибка в onIsValidVoiceName", e)
            TextToSpeech.ERROR
        }
    }

    override fun onLoadVoice(voiceName: String?): Int {
        FileLogger.log("onLoadVoice: $voiceName")
        return try {
            if (onIsValidVoiceName(voiceName) != TextToSpeech.SUCCESS) {
                FileLogger.log("Голос не валиден")
                return TextToSpeech.ERROR
            }
            currentVoiceName = voiceName
            val v = repo.loadCached().firstOrNull { it.name == voiceName }
            if (v == null) {
                FileLogger.log("Голос не найден в кэше")
                return TextToSpeech.ERROR
            }
            currentLanguage = localeOf(v.locale)
            FileLogger.log("Голос загружен: $voiceName, язык: ${currentLanguage}")
            TextToSpeech.SUCCESS
        } catch (e: Throwable) {
            FileLogger.error("Ошибка в onLoadVoice", e)
            TextToSpeech.ERROR
        }
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        FileLogger.log("onGetDefaultVoiceNameFor: lang=$lang, country=$country")
        return try {
            val voices = selectableVoices()
            if (voices.isEmpty()) {
                FileLogger.log("Нет доступных голосов")
                return null
            }
            val match = voices.firstOrNull { v ->
                val loc = localeOf(v.locale)
                iso3Lang(loc).equals(lang, true) &&
                        (country.isNullOrBlank() || iso3Country(loc).equals(country, true))
            } ?: voices.firstOrNull { v ->
                iso3Lang(localeOf(v.locale)).equals(lang, true)
            }
            FileLogger.log("Найден голос по умолчанию: ${match?.name}")
            match?.name
        } catch (e: Throwable) {
            FileLogger.error("Ошибка в onGetDefaultVoiceNameFor", e)
            null
        }
    }

    override fun onGetLanguage(): Array<String> {
        val result = arrayOf(
            iso3Lang(currentLanguage),
            iso3Country(currentLanguage),
            currentLanguage.variant ?: ""
        )
        FileLogger.log("onGetLanguage: ${result.joinToString()}")
        return result
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        FileLogger.log("onIsLanguageAvailable: lang=$lang, country=$country")
        return try {
            if (lang.isNullOrBlank()) {
                FileLogger.log("Язык пустой")
                return TextToSpeech.LANG_NOT_SUPPORTED
            }
            val voices = selectableVoices()
            if (voices.isEmpty()) {
                FileLogger.log("Нет голосов в кэше")
                return TextToSpeech.LANG_MISSING_DATA
            }
            val langMatch = voices.filter { iso3Lang(localeOf(it.locale)).equals(lang, true) }
            if (langMatch.isEmpty()) {
                FileLogger.log("Язык не поддерживается")
                return TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (!country.isNullOrBlank()) {
                val countryMatch = langMatch.any { iso3Country(localeOf(it.locale)).equals(country, true) }
                if (countryMatch) {
                    FileLogger.log("Язык и страна доступны")
                    return TextToSpeech.LANG_COUNTRY_AVAILABLE
                }
            }
            FileLogger.log("Только язык доступен")
            TextToSpeech.LANG_AVAILABLE
        } catch (e: Throwable) {
            FileLogger.error("Ошибка в onIsLanguageAvailable", e)
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        FileLogger.log("onLoadLanguage: lang=$lang, country=$country")
        return try {
            val status = onIsLanguageAvailable(lang, country, variant)
            if (status < TextToSpeech.LANG_AVAILABLE) {
                FileLogger.log("Язык недоступен, статус: $status")
                return status
            }
            val voices = selectableVoices()
            val chosen = voices.firstOrNull { v ->
                val loc = localeOf(v.locale)
                iso3Lang(loc).equals(lang, true) &&
                        (country.isNullOrBlank() || iso3Country(loc).equals(country, true))
            } ?: voices.firstOrNull { iso3Lang(localeOf(it.locale)).equals(lang, true) }
            if (chosen != null) {
                currentLanguage = localeOf(chosen.locale)
                currentVoiceName = chosen.name
                FileLogger.log("Язык загружен: ${currentLanguage}, голос: $currentVoiceName")
            } else {
                FileLogger.log("Голос для языка не найден")
            }
            status
        } catch (e: Throwable) {
            FileLogger.error("Ошибка в onLoadLanguage", e)
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        FileLogger.log("=== onSynthesizeText вызван ===")
        FileLogger.log("voiceName из запроса: ${request.voiceName}")
        FileLogger.log("language: ${request.language}, country: ${request.country}")

        val voiceName = request.voiceName
            ?.takeIf { it.isNotBlank() }
            ?: currentVoiceName
            ?: onGetDefaultVoiceNameFor(request.language, request.country, request.variant)

        if (voiceName.isNullOrBlank()) {
            FileLogger.error("Не удалось определить голос")
            callback.error()
            return
        }

        FileLogger.log("Используем голос: $voiceName")

        Thread {
            try {
                val text = request.charSequenceText?.toString().orEmpty()
                FileLogger.log("Текст для синтеза: '$text'")

                if (text.isBlank()) {
                    FileLogger.log("Текст пустой")
                    callback.done()
                    return@Thread
                }

                val rateStr = rate(request.params)
                val t0 = System.currentTimeMillis()

                // 1) Заготовка из кэша (читалка заранее передала тексты в "nova_prefetch")
                //    — либо живой запрос. Живая фраза идёт первой: заготовки на
                //    следующие планируем только когда её сеть уже отработала.
                var decoded: Pair<ByteArray, Int>? = null
                val fut = prefetch.take(voiceName, rateStr, text)
                if (fut != null) {
                    val wasReady = fut.isDone
                    try {
                        decoded = fut.get(30, java.util.concurrent.TimeUnit.SECONDS)
                        FileLogger.log("Из предзагрузки (${if (wasReady) "готова" else "ждали"}), " +
                            "${System.currentTimeMillis() - t0} мс")
                    } catch (e: Throwable) {
                        FileLogger.log("Заготовка не подошла (${e.javaClass.simpleName}) — живой запрос")
                        prefetch.drop(voiceName, rateStr, text)
                    }
                }
                if (decoded == null) {
                    FileLogger.log("Вызываем EdgeTTSClient.synthesize...")
                    val mp3 = client.synthesize(text = text, voice = voiceName, rate = rateStr)
                    FileLogger.log("Получено MP3: ${mp3.size} байт")
                    FileLogger.log("Декодируем MP3...")
                    decoded = Mp3Decoder.decode(mp3)
                }
                val (pcm, sampleRate) = decoded!!
                FileLogger.log("Декодировано PCM: ${pcm.size} байт, sampleRate: $sampleRate Hz; " +
                    "до готовности звука ${System.currentTimeMillis() - t0} мс")

                // 2) Теперь, когда текущая фраза готова, — фон для следующих
                val hints = parseHints(request.params)
                if (hints.isNotEmpty()) prefetch.schedule(voiceName, rateStr, hints)

                FileLogger.log("Вызываем callback.start...")
                val startResult = callback.start(
                    sampleRate,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    1
                )
                FileLogger.log("callback.start результат: $startResult")

                // Система ограничивает размер одного буфера (обычно ~8 КБ) — берём
                // предел у самого callback, а не фиксированные 32 КБ (иначе
                // IllegalArgumentException: buffer is too large). Размер чётный: 16 бит на отсчёт.
                val maxChunk = (callback.maxBufferSize.coerceAtLeast(2) and 1.inv()).coerceAtLeast(2)
                FileLogger.log("maxBufferSize: ${callback.maxBufferSize}, чанк: $maxChunk")
                var offset = 0
                var chunkIndex = 0
                while (offset < pcm.size) {
                    val chunk = minOf(maxChunk, pcm.size - offset)
                    val result = callback.audioAvailable(pcm, offset, chunk)
                    FileLogger.log("Чанк $chunkIndex: offset=$offset, size=$chunk, result=$result")
                    if (result != TextToSpeech.SUCCESS) {
                        FileLogger.error("audioAvailable вернул ошибку: $result")
                        break
                    }
                    offset += chunk
                    chunkIndex++
                }

                FileLogger.log("Вызываем callback.done...")
                callback.done()
                FileLogger.log("=== Синтез завершён успешно ===")
            } catch (e: Throwable) {
                FileLogger.error("Ошибка при синтезе", e)
                try {
                    callback.error()
                    FileLogger.log("Вызван callback.error")
                } catch (e2: Throwable) {
                    FileLogger.error("Ошибка при вызове callback.error", e2)
                }
            }
        }.start()
    }

    override fun onStop() {
        FileLogger.log("onStop вызван")
    }

    private fun selectableVoices(): List<EdgeVoice> {
        val selected = repo.selected()
        val all = repo.loadCached()
        return if (selected.isNotEmpty()) all.filter { it.name in selected } else all
    }

    private fun localeOf(tag: String): Locale =
        Locale.forLanguageTag(tag.replace('_', '-'))

    private fun iso3Lang(locale: Locale): String = try {
        locale.isO3Language
    } catch (_: MissingResourceException) {
        locale.language
    }

    private fun iso3Country(locale: Locale): String = try {
        if (locale.country.isEmpty()) "" else locale.isO3Country
    } catch (_: MissingResourceException) {
        locale.country
    }

    /** Тексты следующих фраз из параметров speak() (ключ "nova_prefetch", JSON-массив). */
    private fun parseHints(params: Bundle?): List<String> {
        val raw = params?.getString("nova_prefetch") ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Throwable) {
            FileLogger.log("nova_prefetch: не разобран (${e.javaClass.simpleName})")
            emptyList()
        }
    }

    private fun rate(params: Bundle?): String {
        val percent = params?.getInt("rate", 100) ?: 100
        val edgePercent = percent - 100
        val rateStr = "%+d%%".format(Locale.US, edgePercent)
        FileLogger.log("Rate: $percent% -> Edge: $rateStr")
        return rateStr
    }
}