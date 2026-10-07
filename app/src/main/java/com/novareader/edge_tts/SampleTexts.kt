package com.novareader.edge_tts

import java.util.Locale

/**
 * Примеры фраз для проверки голоса в системных настройках Android.
 *
 * Почему это нужно: настройки Android при нажатии «Воспроизвести» часто присылают
 * СВОЙ стандартный пример на языке интерфейса телефона (например, русский
 * «Это пример синтеза речи.») даже для голоса другого языка. Обычные голоса Edge
 * не читают чужую письменность (африкаанс-голос получает кириллицу → сервер
 * отвечает без аудио). Для такой проверки подставляем пример на языке голоса.
 */
object SampleTexts {
    private val map = mapOf(
        "af" to "Dit is 'n voorbeeld van spraaksintese.",
        "am" to "ይህ የንግግር ውህደት ምሳሌ ነው።",
        "ar" to "هذا مثال على تركيب الكلام.",
        "az" to "Bu nitq sintezinin nümunəsidir.",
        "bg" to "Това е пример за синтез на реч.",
        "bn" to "এটি বক্তৃতা সংশ্লেষণের একটি উদাহরণ।",
        "bs" to "Ovo je primjer sinteze govora.",
        "ca" to "Aquest és un exemple de síntesi de veu.",
        "cs" to "Toto je příklad syntézy řeči.",
        "cy" to "Dyma enghraifft o synthesis lleferydd.",
        "da" to "Dette er et eksempel på talesyntese.",
        "de" to "Dies ist ein Beispiel für Sprachsynthese.",
        "el" to "Αυτό είναι ένα παράδειγμα σύνθεσης ομιλίας.",
        "en" to "This is an example of speech synthesis.",
        "es" to "Este es un ejemplo de síntesis de voz.",
        "et" to "See on kõnesünteesi näide.",
        "fa" to "این یک نمونه از تبدیل متن به گفتار است.",
        "fi" to "Tämä on esimerkki puhesynteesistä.",
        "fil" to "Ito ay isang halimbawa ng speech synthesis.",
        "fr" to "Ceci est un exemple de synthèse vocale.",
        "ga" to "Is sampla é seo de sintéis chainte.",
        "gl" to "Este é un exemplo de síntese de voz.",
        "gu" to "આ વાણી સંશ્લેષણનું ઉદાહરણ છે.",
        "he" to "זוהי דוגמה לסינתזת דיבור.",
        "hi" to "यह वाक् संश्लेषण का एक उदाहरण है।",
        "hr" to "Ovo je primjer sinteze govora.",
        "hu" to "Ez egy példa a beszédszintézisre.",
        "id" to "Ini adalah contoh sintesis ucapan.",
        "is" to "Þetta er dæmi um talgervingu.",
        "it" to "Questo è un esempio di sintesi vocale.",
        "ja" to "これは音声合成の例です。",
        "jv" to "Iki conto sintesis wicara.",
        "ka" to "ეს არის მეტყველების სინთეზის მაგალითი.",
        "kk" to "Бұл сөйлеуді синтездеудің мысалы.",
        "km" to "នេះគឺជាឧទាហរណ៍នៃការសំយោគសំដី។",
        "kn" to "ಇದು ಮಾತಿನ ಸಂಶ್ಲೇಷಣೆಯ ಉದಾಹರಣೆಯಾಗಿದೆ.",
        "ko" to "이것은 음성 합성의 예입니다.",
        "lo" to "ນີ້ແມ່ນຕົວຢ່າງຂອງການສັງເຄາະສຽງເວົ້າ.",
        "lt" to "Tai kalbos sintezės pavyzdys.",
        "lv" to "Šis ir runas sintēzes piemērs.",
        "mk" to "Ова е пример за синтеза на говор.",
        "ml" to "ഇത് സംഭാഷണ സംശ്ലേഷണത്തിന്റെ ഒരു ഉദാഹരണമാണ്.",
        "mn" to "Энэ бол хэл ярианы синтезийн жишээ юм.",
        "mr" to "हे वाक् संश्लेषणाचे उदाहरण आहे.",
        "ms" to "Ini ialah contoh sintesis pertuturan.",
        "mt" to "Dan huwa eżempju ta' sintesi tal-kliem.",
        "my" to "ဤသည်မှာ စကားပြောပေါင်းစပ်မှု၏ နမူနာဖြစ်သည်။",
        "nb" to "Dette er et eksempel på talesyntese.",
        "ne" to "यो वाक् संश्लेषणको उदाहरण हो।",
        "nl" to "Dit is een voorbeeld van spraaksynthese.",
        "pl" to "To jest przykład syntezy mowy.",
        "ps" to "دا د وینا د ترکیب یوه بېلګه ده.",
        "pt" to "Este é um exemplo de síntese de fala.",
        "ro" to "Acesta este un exemplu de sinteză vocală.",
        "ru" to "Это пример синтеза речи.",
        "si" to "මෙය කථන සංස්ලේෂණයේ උදාහරණයකි.",
        "sk" to "Toto je príklad syntézy reči.",
        "sl" to "To je primer sinteze govora.",
        "so" to "Kani waa tusaale isku-dubaridka hadalka.",
        "sq" to "Ky është një shembull i sintezës së të folurit.",
        "sr" to "Ово је пример синтезе говора.",
        "su" to "Ieu conto sintésis ucapan.",
        "sv" to "Det här är ett exempel på talsyntes.",
        "sw" to "Huu ni mfano wa usanisi wa usemi.",
        "ta" to "இது பேச்சு தொகுப்பின் ஒரு எடுத்துக்காட்டு.",
        "te" to "ఇది ప్రసంగ సంశ్లేషణకు ఒక ఉదాహరణ.",
        "th" to "นี่คือตัวอย่างของการสังเคราะห์เสียงพูด",
        "tr" to "Bu, konuşma sentezi örneğidir.",
        "uk" to "Це приклад синтезу мовлення.",
        "ur" to "یہ تقریر کی تالیف کی ایک مثال ہے۔",
        "uz" to "Bu nutq sintezi namunasidir.",
        "vi" to "Đây là một ví dụ về tổng hợp giọng nói.",
        "zh" to "这是语音合成的示例。",
        "zu" to "Lesi isibonelo sokuhlanganiswa kwenkulumo.",
    )

    // Стандартные примеры самих настроек Android (на языках интерфейса) → их язык
    private val platformDefaults = mapOf(
        "Это пример синтеза речи." to "ru",
        "This is an example of speech synthesis." to "en",
    )

    /** ISO-2 код ("af") из ISO-2 или ISO-3 ("afr") — либо исходная строка в нижнем регистре. */
    fun iso2(code: String): String {
        val c = code.lowercase(Locale.ROOT)
        if (c.length == 2) return c
        return Locale.getISOLanguages().firstOrNull {
            try { Locale(it).isO3Language == c } catch (_: Exception) { false }
        } ?: c
    }

    fun forLanguage(code: String): String? = map[iso2(code)]

    /**
     * Если text — стандартный пример Android на языке, не совпадающем с языком голоса,
     * возвращает пример на языке голоса; иначе null (текст не трогаем).
     */
    fun replacementFor(text: String, voiceName: String): String? {
        val textLang = platformDefaults[text.trim()] ?: return null
        val voiceLang = iso2(voiceName.substringBefore('-'))
        if (voiceLang == textLang) return null
        return map[voiceLang]
    }

    /** Грубое определение письменности по первой букве: L(атиница), C(ирилл.), иначе — код блока Unicode. */
    private fun scriptOf(text: String): String? {
        val ch = text.firstOrNull { it.isLetter() } ?: return null
        return try { Character.UnicodeScript.of(ch.code).name } catch (_: Exception) { null }
    }

    /**
     * Запрос пришёл из системных настроек (пример на языке интерфейса телефона, любом —
     * немецком, китайском, арабском…). Если письменность текста не совпадает с письменностью
     * языка голоса и голос не Multilingual, он ничего не прочтёт — подставляем пример на языке голоса.
     * Для обычной книги (callerIsSettings = false) текст не трогаем.
     */
    fun replacementForSettings(text: String, voiceName: String, callerIsSettings: Boolean): String? {
        if (!callerIsSettings || text.length > 120 || voiceName.contains("Multilingual", true)) return null
        val own = map[iso2(voiceName.substringBefore('-'))] ?: return null
        val a = scriptOf(text) ?: return null
        val b = scriptOf(own) ?: return null
        return if (a != b) own else null
    }
}
