package com.novareader.edge_tts

import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Клиент Edge Read Aloud — Kotlin-аналог `edge_tts/communicate.py` (Python-пакет edge-tts 7.3.1).
 *
 * Что сверялось с пакетом: Sec-MS-GEC v2 ([EdgeGec]) и `Sec-MS-GEC-Version=2-…`, заголовки WSS,
 * speech.config (SentenceBoundary), SSML, нарезка текста по 4096 байт, разбор бинарных кадров,
 * повтор после 403 с поправкой часов. При выходе новой версии пакета в первую очередь сверять
 * `constants.py` и `drm.py` (их значения собраны в [EdgeGec]).
 */
class EdgeTTSClient {
    companion object {
        private const val HOST = "speech.platform.bing.com"
        private const val FORMAT = "audio-24khz-48kbitrate-mono-mp3"
        /** Как в Communicate.__init__: текст режется на куски не больше 4096 байт (после XML-экранирования). */
        private const val MAX_CHUNK_BYTES = 4096
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    private class HandshakeRejected(val status: Int, val serverDate: String?, msg: String) : IllegalStateException(msg)

    fun synthesize(text: String, voice: String, rate: String = "+0%", pitch: String = "+0Hz", volume: String = "+0%"): ByteArray {
        val chunks = splitByByteLength(escape(removeIncompatibleCharacters(text)), MAX_CHUNK_BYTES)
        if (chunks.isEmpty()) error("Edge: нет текста для синтеза")
        if (chunks.size == 1) return synthesizeChunk(chunks[0], voice, rate, pitch, volume)
        FileLogger.log("EdgeTTS: текст длинный — ${chunks.size} запросов")
        val out = ByteArrayOutputStream()
        for (c in chunks) out.write(synthesizeChunk(c, voice, rate, pitch, volume))
        return out.toByteArray()
    }

    /** Один запрос; при 403 — подстройка часов по Date сервера и один повтор (как Communicate.stream). */
    private fun synthesizeChunk(escapedText: String, voice: String, rate: String, pitch: String, volume: String): ByteArray {
        try {
            return synthesizeOnce(escapedText, voice, rate, pitch, volume)
        } catch (e: HandshakeRejected) {
            if (e.status == 403 && EdgeGec.adjustClockFromServerDate(e.serverDate)) {
                FileLogger.log("EdgeTTS: 403 — поправка часов ${EdgeGec.skewSeconds()} с, повтор")
                return synthesizeOnce(escapedText, voice, rate, pitch, volume)
            }
            throw e
        }
    }

    private fun synthesizeOnce(escapedText: String, voice: String, rate: String, pitch: String, volume: String): ByteArray {
        FileLogger.log("EdgeTTS: Начало синтеза для голоса $voice")

        val connId = UUID.randomUUID().toString().replace("-", "")
        val gec = EdgeGec.currentToken()
        FileLogger.log("EdgeTTS: GEC=$gec, ConnectionId=$connId")

        val path = "/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=${EdgeGec.TRUSTED_CLIENT_TOKEN}" +
            "&ConnectionId=$connId&Sec-MS-GEC=$gec&Sec-MS-GEC-Version=${EdgeGec.SEC_MS_GEC_VERSION}"

        // Таймаут подключения 10 с (sock_connect в пакете), чтение — 60 с (sock_read).
        val raw = Socket()
        val socket: SSLSocket
        try {
            raw.connect(InetSocketAddress(HOST, 443), CONNECT_TIMEOUT_MS)
            socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, HOST, 443, true) as SSLSocket
            socket.soTimeout = READ_TIMEOUT_MS
            socket.startHandshake()
        } catch (e: Exception) {
            try { raw.close() } catch (_: Exception) {}
            throw e
        }
        // Проверка имени хоста (для «сырого» SSLSocket её нужно делать вручную)
        if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, socket.session)) {
            socket.close(); error("TLS: имя хоста не совпало с сертификатом")
        }

        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())

        try {
            val keyBytes = ByteArray(16)
            SecureRandom().nextBytes(keyBytes)
            val key = Base64.getEncoder().encodeToString(keyBytes)

            // Заголовки — как WSS_HEADERS (+ BASE_HEADERS и muid) в edge-tts. Лишние Client Hints /
            // Sec-Fetch-* и Sec-WebSocket-Protocol НЕ отправляем: настоящий клиент их не шлёт,
            // а сервер сверяет отпечаток запроса.
            val req = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: $HOST\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $key\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Pragma: no-cache\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n")
                append("User-Agent: ${EdgeGec.USER_AGENT}\r\n")
                append("Accept-Encoding: gzip, deflate, br, zstd\r\n")
                append("Accept-Language: en-US,en;q=0.9\r\n")
                append("Cookie: muid=${EdgeGec.muid()};\r\n")
                append("\r\n")
            }

            FileLogger.log("EdgeTTS: Отправка WebSocket handshake")
            FileLogger.log("EdgeTTS: Path=$path")
            output.write(req.toByteArray(Charsets.US_ASCII))
            output.flush()

            val response = readHttpHeaders(input)
            FileLogger.log("EdgeTTS: Получен ответ: ${response.substringBefore("\n")}")

            if (!response.startsWith("HTTP/1.1 101")) {
                val status = response.substringBefore("\n").split(" ").getOrNull(1)?.toIntOrNull() ?: 0
                val dateHdr = response.lineSequence()
                    .firstOrNull { it.startsWith("Date:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()
                throw HandshakeRejected(status, dateHdr, "Edge WebSocket handshake failed: $response")
            }

            FileLogger.log("EdgeTTS: WebSocket соединение установлено")

            // speech.config — как send_command_request(): по умолчанию в пакете SentenceBoundary.
            val configJson = """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"true","wordBoundaryEnabled":"false"},"outputFormat":"$FORMAT"}}}}"""
            sendText(output, "X-Timestamp:${now()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n$configJson\r\n")
            FileLogger.log("EdgeTTS: Конфигурация отправлена")

            // SSML — как mkssml(): xml:lang всегда en-US (язык определяет голос), одинарные кавычки.
            val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='$voice'><prosody pitch='$pitch' rate='$rate' volume='$volume'>$escapedText</prosody></voice></speak>"

            FileLogger.log("EdgeTTS: Отправка SSML")
            // «Z» после даты — не ошибка, а баг самого Microsoft Edge (см. ssml_headers_plus_data).
            sendText(output, "X-RequestId:${UUID.randomUUID().toString().replace("-", "")}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${now()}Z\r\nPath:ssml\r\n\r\n$ssml")

            // Читаем аудио
            val audio = ByteArrayOutputStream()
            var frameCount = 0

            while (true) {
                val frame = readFrame(input) ?: break
                frameCount++

                when (frame.first) {
                    1 -> {
                        val s = String(frame.second, Charsets.UTF_8)
                        if (s.contains("Path:turn.end")) {
                            FileLogger.log("EdgeTTS: Получен turn.end")
                            break
                        }
                        // audio.metadata (границы предложений) нам не нужны — не засоряем лог
                        if (!s.contains("Path:audio.metadata")) {
                            FileLogger.log("EdgeTTS: Текстовый фрейм: ${s.substring(0, minOf(100, s.length))}")
                        }
                    }
                    2 -> {
                        val d = frame.second
                        if (d.size < 2) continue
                        val h = ((d[0].toInt() and 255) shl 8) or (d[1].toInt() and 255)
                        if (h + 2 > d.size) continue
                        // Как в __stream(): учитываем только Path:audio; Content-Type audio/mpeg
                        // (кадр без Content-Type и без данных — признак конца потока, пропускаем).
                        val headers = parseBinaryHeaders(d, h)
                        if (headers["Path"] != "audio") {
                            FileLogger.log("EdgeTTS: бинарный кадр с Path=${headers["Path"]} пропущен")
                            continue
                        }
                        val contentType = headers["Content-Type"]
                        if (contentType != null && contentType != "audio/mpeg") {
                            FileLogger.log("EdgeTTS: бинарный кадр с Content-Type=$contentType пропущен")
                            continue
                        }
                        val len = d.size - h - 2
                        if (len > 0 && contentType != null) {
                            audio.write(d, h + 2, len)
                            if (frameCount % 10 == 0) FileLogger.log("EdgeTTS: Получено аудио: ${audio.size()} байт")
                        }
                    }
                    9 -> sendControl(output, 10, frame.second)
                    8 -> {
                        FileLogger.log("EdgeTTS: Получен close frame")
                        break
                    }
                }
            }

            FileLogger.log("EdgeTTS: Всего фреймов: $frameCount, аудио: ${audio.size()} байт")

            if (audio.size() == 0) {
                // Типичная причина: голос не «Multilingual» и получил текст на другом языке
                // (например, английский голос — русский текст): Edge отвечает turn.end без аудио.
                error("Edge returned no audio for voice=$voice, text='${escapedText.take(40)}' " +
                    "(если язык текста не совпадает с языком голоса — выберите голос ...MultilingualNeural)")
            }

            FileLogger.log("EdgeTTS: Синтез завершён успешно")
            return audio.toByteArray()
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // Как date_to_string(): JS-стиль даты.
    private fun now() = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }
        .format(Date())

    /** Заголовки бинарного кадра: [2 байта длины][заголовки, h байт, с хвостовым CRLF][данные]. */
    private fun parseBinaryHeaders(d: ByteArray, h: Int): Map<String, String> {
        val m = HashMap<String, String>()
        for (line in String(d, 2, h, Charsets.UTF_8).split("\r\n")) {
            val i = line.indexOf(':')
            if (i > 0) m[line.substring(0, i)] = line.substring(i + 1)
        }
        return m
    }

    /** XML-экранирование как xml.sax.saxutils.escape: только & < >. */
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Как remove_incompatible_characters(): сервис не принимает часть управляющих символов
     * (например, вертикальную табуляцию из OCR-PDF) — заменяем их пробелом.
     */
    private fun removeIncompatibleCharacters(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = ch.code
            sb.append(if (c in 0..8 || c in 11..12 || c in 14..31) ' ' else ch)
        }
        return sb.toString()
    }

    /**
     * Как split_text_by_byte_length(): куски не длиннее [limit] байт UTF-8; режем по
     * переводу строки, иначе по пробелу, иначе по границе символа; не разрываем XML-сущности (&amp;).
     */
    internal fun splitByByteLength(text: String, limit: Int): List<String> {
        require(limit > 0) { "limit must be > 0" }
        var bytes = text.toByteArray(Charsets.UTF_8)
        val out = ArrayList<String>()
        while (bytes.size > limit) {
            var split = lastIndexOfByte(bytes, '\n'.code.toByte(), limit)
            if (split < 0) split = lastIndexOfByte(bytes, ' '.code.toByte(), limit)
            if (split < 0) {
                // Нет ни перевода строки, ни пробела (например, китайский текст): режем по границе символа.
                // (В пакете здесь проверяется весь текст целиком — у нас только первые limit байт.)
                split = limit
                while (split > 0 && (bytes[split].toInt() and 0xC0) == 0x80) split--
            }
            // Не режем внутри «&…;»
            while (split > 0) {
                val amp = lastIndexOfByte(bytes, '&'.code.toByte(), split)
                if (amp < 0) break
                var terminated = false
                for (i in amp until split) if (bytes[i] == ';'.code.toByte()) { terminated = true; break }
                if (terminated) break
                split = amp
            }
            if (split <= 0) split = minOf(limit, bytes.size) // страховка от зацикливания
            val chunk = String(bytes, 0, split, Charsets.UTF_8).trim()
            if (chunk.isNotEmpty()) out += chunk
            bytes = bytes.copyOfRange(split, bytes.size)
        }
        val rest = String(bytes, Charsets.UTF_8).trim()
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /** Последнее вхождение [b] в bytes[0, endExclusive) или -1. */
    private fun lastIndexOfByte(bytes: ByteArray, b: Byte, endExclusive: Int): Int {
        var i = minOf(endExclusive, bytes.size) - 1
        while (i >= 0) { if (bytes[i] == b) return i; i-- }
        return -1
    }

    private fun readHttpHeaders(input: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val x = input.read()
            if (x < 0) error("EOF in WebSocket handshake")
            b.write(x)
            val n = b.size()
            if (n >= 4) {
                val a = b.toByteArray()
                if (a[n - 4].toInt() == 13 && a[n - 3].toInt() == 10 &&
                    a[n - 2].toInt() == 13 && a[n - 1].toInt() == 10) {
                    return String(a, Charsets.US_ASCII).trim()
                }
            }
        }
    }

    private fun sendText(out: OutputStream, s: String) = sendFrame(out, 1, s.toByteArray())

    private fun sendControl(out: OutputStream, opcode: Int, data: ByteArray) = sendFrame(out, opcode, data)

    private fun sendFrame(out: OutputStream, opcode: Int, data: ByteArray) {
        val mask = ByteArray(4)
        SecureRandom().nextBytes(mask)
        val first = (0x80 or opcode).toByte()
        out.write(first.toInt())

        val len = data.size
        if (len < 126) {
            out.write(0x80 or len)
        } else if (len <= 65535) {
            out.write(0x80 or 126)
            out.write(len ushr 8)
            out.write(len)
        } else {
            out.write(0x80 or 127)
            for (i in 7 downTo 0) {
                out.write((len.toLong() ushr (8 * i)).toInt())
            }
        }

        out.write(mask)
        for (i in data.indices) {
            out.write(data[i].toInt() xor mask[i and 3].toInt())
        }
        out.flush()
    }

    private fun readFrame(input: InputStream): Pair<Int, ByteArray>? {
        val a = input.read()
        if (a < 0) return null
        val b = input.read()
        if (b < 0) return null

        val op = a and 15
        var len = (b and 127).toLong()

        if (len == 126L) {
            len = ((input.read() shl 8) or input.read()).toLong()
        } else if (len == 127L) {
            len = 0
            repeat(8) {
                len = (len shl 8) or input.read().toLong()
            }
        }

        val masked = (b and 128) != 0
        val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null

        if (len > 20_000_000) error("WebSocket frame too large")

        val d = ByteArray(len.toInt())
        readFully(input, d)

        if (masked && mask != null) {
            for (i in d.indices) {
                d[i] = (d[i].toInt() xor mask[i and 3].toInt()).toByte()
            }
        }

        return op to d
    }

    private fun readFully(i: InputStream, b: ByteArray) {
        var p = 0
        while (p < b.size) {
            val n = i.read(b, p, b.size - p)
            if (n < 0) error("Unexpected EOF")
            p += n
        }
    }
}
