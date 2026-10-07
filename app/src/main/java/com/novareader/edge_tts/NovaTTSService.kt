package com.novareader.edge_tts

import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.MissingResourceException

class NovaTTSService : TextToSpeechService() {

    private val repo: EdgeVoiceRepository by lazy {
        FileLogger.log("Инициализация EdgeVoiceRepository")
        EdgeVoiceRepository(this)
    }
    private val client = EdgeTTSClient()
    private val prefetch = EdgePrefetch(client)
    // NovaEdgeTTS is voice-first: every supported entry is a concrete Edge voice.
    // Start with the first selected voice instead of the phone/system locale.
    private var currentLanguage = Locale("en", "US", "default")
    private var currentVoiceName: String? = null

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        FileLogger.log("NovaTTSService создан")

        // Do not inherit the Android phone locale as NovaEdgeTTS's language.
        // The engine is voice-first: choose one concrete Edge voice as its
        // initial/default voice. This prevents the Settings "system language"
        // from becoming an implicit engine language.
        try {
            val first = selectableVoices().firstOrNull()
            if (first != null) {
                currentVoiceName = first.name
                currentLanguage = voiceLocale(first)
                FileLogger.log("Начальный голос: ${first.name}, locale=${currentLanguage}")
            } else {
                FileLogger.log("Нет выбранных голосов при создании движка")
            }
        } catch (e: Throwable) {
            FileLogger.error("Не удалось выбрать начальный голос", e)
        }

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
                    voiceLocale(v),
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
            currentLanguage = voiceLocale(v)
            FileLogger.log("Голос загружен: $voiceName, язык: ${currentLanguage}")
            TextToSpeech.SUCCESS
        } catch (e: Throwable) {
            FileLogger.error("Ошибка в onLoadVoice", e)
            TextToSpeech.ERROR
        }
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        FileLogger.log("onGetDefaultVoiceNameFor: lang=$lang, country=$country, variant=$variant")
        return try {
            val voices = selectableVoices()
            if (voices.isEmpty() || lang.isNullOrBlank()) return null

            // A concrete voice is identified by its variant. If Android gives us
            // one, it MUST win; never substitute another voice of the same locale.
            if (!variant.isNullOrBlank()) {
                val exact = voices.firstOrNull { v ->
                    val loc = voiceLocale(v)
                    iso3Lang(loc).equals(lang, true) &&
                        iso3Country(loc).equals(country ?: "", true) &&
                        loc.variant.equals(variant, true)
                }
                FileLogger.log("Точный голос по variant: ${exact?.name}")
                return exact?.name
            }

            // A generic language has no concrete Edge voice. Android must resolve
            // generic requests from the concrete voices returned by onGetVoices().
            FileLogger.log("Общий язык без variant не поддерживается как отдельный голос")
            null
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
        FileLogger.log("onIsLanguageAvailable: lang=$lang, country=$country, variant=$variant")
        return try {
            if (lang.isNullOrBlank()) return TextToSpeech.LANG_NOT_SUPPORTED
            if (variant.isNullOrBlank()) {
                FileLogger.log("Общий язык без variant не поддерживается")
                return TextToSpeech.LANG_NOT_SUPPORTED
            }

            val exact = selectableVoices().firstOrNull { v ->
                val loc = voiceLocale(v)
                iso3Lang(loc).equals(lang, true) &&
                    iso3Country(loc).equals(country ?: "", true) &&
                    loc.variant.equals(variant, true)
            }

            if (exact != null) {
                FileLogger.log("Конкретный голос доступен: ${exact.name}")
                TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            } else {
                FileLogger.log("Конкретный голос недоступен")
                TextToSpeech.LANG_NOT_SUPPORTED
            }
        } catch (e: Throwable) {
            FileLogger.error("Ошибка в onIsLanguageAvailable", e)
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        FileLogger.log("onLoadLanguage: lang=$lang, country=$country, variant=$variant")
        return try {
            if (lang.isNullOrBlank()) return TextToSpeech.LANG_NOT_SUPPORTED
            if (variant.isNullOrBlank()) {
                FileLogger.log("Язык без конкретного голоса отклонён")
                return TextToSpeech.LANG_NOT_SUPPORTED
            }

            val chosen = selectableVoices().firstOrNull { v ->
                val loc = voiceLocale(v)
                iso3Lang(loc).equals(lang, true) &&
                    iso3Country(loc).equals(country ?: "", true) &&
                    loc.variant.equals(variant, true)
            } ?: return TextToSpeech.LANG_NOT_SUPPORTED

            currentLanguage = voiceLocale(chosen)
            currentVoiceName = chosen.name
            FileLogger.log("Загружен конкретный голос: ${chosen.name}, locale=${currentLanguage}")
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        } catch (e: Throwable) {
            FileLogger.error("Ошибка в onLoadLanguage", e)
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        FileLogger.log("=== onSynthesizeText вызван ===")
        FileLogger.log("voiceName из запроса: ${request.voiceName}")
        FileLogger.log("language: ${request.language}, country: ${request.country}")

        // Android does not always put Voice.name into SynthesisRequest.
        // In particular, Settings can select a voice by its
        // language/country/variant and then call synthesis with voiceName == null.
        // Resolve the exact Edge voice from the requested variant BEFORE falling
        // back to currentVoiceName; otherwise the previously loaded voice can
        // silently win (e.g. Dmitry is spoken after selecting Svetlana).
        val voiceName = resolveVoiceForRequest(request)

        if (voiceName.isNullOrBlank()) {
            FileLogger.error("Не удалось определить голос")
            callback.error()
            return
        }

        FileLogger.log("Используем голос: $voiceName")

        Thread {
            try {
                var text = request.charSequenceText?.toString().orEmpty()
                FileLogger.log("Текст для синтеза: '$text'")
                // Проверка голоса из настроек Android: система шлёт свой пример на языке
                // интерфейса, а голос другого языка его не прочтёт — подставляем пример на языке голоса.
                val fromSettings = try {
                    packageManager.getPackagesForUid(request.callerUid)
                        ?.any { it.contains("settings", true) } == true
                } catch (_: Exception) { false }
                (SampleTexts.replacementFor(text, voiceName)
                    ?: SampleTexts.replacementForSettings(text, voiceName, fromSettings))?.let {
                    FileLogger.log("Пример на другом языке заменён на язык голоса: '$it'")
                    text = it
                }

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

    /**
     * Resolves the Edge ShortName that Android actually wants for this synthesis
     * request. Android may identify our synthetic voices either by Edge ShortName
     * (e.g. ru-RU-SvetlanaNeural) or by the TTS locale triplet
     * (e.g. rus-RUS-Svetlana). The latter must be mapped back to the Edge voice.
     */
    private fun resolveVoiceForRequest(request: SynthesisRequest): String? {
        val voices = selectableVoices()
        if (voices.isEmpty()) return null

        val requestedName = request.voiceName?.takeIf { it.isNotBlank() }
        if (requestedName != null) {
            voices.firstOrNull { it.name.equals(requestedName, true) }?.let {
                FileLogger.log("Точный Edge voiceName из запроса: ${it.name}")
                return it.name
            }

            // Some Android versions may pass our synthetic locale/variant as
            // voiceName instead of the original Edge ShortName.
            voices.firstOrNull { syntheticVoiceId(it).equals(requestedName, true) }?.let {
                FileLogger.log("Синтетический voiceName преобразован: $requestedName -> ${it.name}")
                return it.name
            }
        }

        val lang = request.language
        val country = request.country
        val variant = request.variant

        // Most important path: variant identifies the individual Edge voice.
        if (!variant.isNullOrBlank()) {
            voices.firstOrNull { v ->
                val loc = voiceLocale(v)
                iso3Lang(loc).equals(lang, true) &&
                        (country.isNullOrBlank() || iso3Country(loc).equals(country, true)) &&
                        loc.variant.equals(variant, true)
            }?.let {
                FileLogger.log("Голос найден по variant: $variant -> ${it.name}")
                return it.name
            }
        }

        if (variant.isNullOrBlank() && requestedName.isNullOrBlank()) {
            FileLogger.log("Запрос без конкретного variant и voiceName отклонён")
            return null
        }

        FileLogger.log("Запрос без конкретного variant отклонён: голос не определён")
        return null
    }

    private fun syntheticVoiceId(v: EdgeVoice): String {
        val loc = voiceLocale(v)
        return buildString {
            append(iso3Lang(loc))
            if (loc.country.isNotEmpty()) {
                append('-').append(iso3Country(loc))
            }
            if (loc.variant.isNotEmpty()) {
                append('-').append(loc.variant)
            }
        }
    }

    /**
     * Локаль для Voice. Даёт каждому голосу уникальный variant
     * ("ru-RU-svetlana", "ru-RU-dmitry"), чтобы Android TTS в настройках
     * показывал их ОТДЕЛЬНЫМИ строками, а не схлопывал в один "Русский".
     */
    /** Full Android TTS locale for an Edge voice, including the voice variant. */
    private fun voiceLocale(v: EdgeVoice): Locale {
        val clean = v.locale.replace('_', '-')
        val parts = clean.split('-')
        if (parts.size >= 2) {
            // The Edge API stores Locale (ru-RU) separately from ShortName
            // (ru-RU-SvetlanaNeural). Android needs both pieces in Voice.locale.
            val variant = v.name.substringAfterLast('-').removeSuffix("Neural").trim()
            return if (variant.isNotEmpty()) {
                Locale(parts[0], parts[1], variant)
            } else {
                Locale(parts[0], parts[1])
            }
        }
        return Locale.forLanguageTag(clean)
    }

    // Kept for callers that already have a complete lang-country-variant tag.
    private fun localeOf(tag: String): Locale {
        val clean = tag.replace('_', '-')
        val parts = clean.split('-')
        if (parts.size >= 3) {
            return Locale(parts[0], parts[1], parts[2].removeSuffix("Neural"))
        }
        return Locale.forLanguageTag(clean)
    }

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