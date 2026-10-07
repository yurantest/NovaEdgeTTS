package com.novareader.edge_tts

import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import javax.net.ssl.SSLSocketFactory

/** Edge Read Aloud client с корректными Client Hints и исправленным GEC. */
class EdgeTTSClient {
    companion object {
        private const val HOST = "speech.platform.bing.com"
        private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        // Версия Chromium должна быть актуальной: сервер Microsoft отвечает 403 на
        // устаревшие Sec-MS-GEC-Version (в Python-пакете edge-tts её периодически
        // обновляют — сверяйте с edge_tts/constants.py, CHROMIUM_FULL_VERSION).
        private const val CHROMIUM = "143.0.3650.75"
        private const val CHROMIUM_MAJOR = "143"
        // Поправка часов телефона относительно сервера (секунды), вычисляется по
        // заголовку Date из ответа 403 — как DRM.adj_clock_skew_seconds в edge-tts.
        @Volatile private var clockSkewSec: Long = 0L
        private const val FORMAT = "audio-24khz-48kbitrate-mono-mp3"
    }

    private class HandshakeRejected(val status: Int, val serverDateSec: Long?, msg: String) : IllegalStateException(msg)

    fun synthesize(text: String, voice: String, rate: String = "+0%", pitch: String = "+0Hz", volume: String = "+0%"): ByteArray {
        try {
            return synthesizeOnce(text, voice, rate, pitch, volume)
        } catch (e: HandshakeRejected) {
            // 403 часто означает расхождение часов: подстраиваем по Date сервера и повторяем один раз.
            val sd = e.serverDateSec
            if (e.status == 403 && sd != null) {
                val local = System.currentTimeMillis() / 1000L
                clockSkewSec += (sd - (local + clockSkewSec))
                FileLogger.log("EdgeTTS: 403 — поправка часов ${clockSkewSec} с, повтор")
                return synthesizeOnce(text, voice, rate, pitch, volume)
            }
            throw e
        }
    }

    private fun synthesizeOnce(text: String, voice: String, rate: String, pitch: String, volume: String): ByteArray {
        FileLogger.log("EdgeTTS: Начало синтеза для голоса $voice")

        val connId = UUID.randomUUID().toString().replace("-", "")
        val gec = gec()
        FileLogger.log("EdgeTTS: GEC=$gec, ConnectionId=$connId")

        val path = "/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TOKEN&ConnectionId=$connId&Sec-MS-GEC=$gec&Sec-MS-GEC-Version=1-$CHROMIUM"

        val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(HOST, 443) as javax.net.ssl.SSLSocket
        socket.soTimeout = 60000
        socket.startHandshake()
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

            // Заголовки — ровно как у Python-пакета edge-tts (WSS_HEADERS + muid).
            // Лишние Client Hints / Sec-Fetch-* и Sec-WebSocket-Protocol НЕ отправляем:
            // настоящий клиент их не шлёт, а сервер сверяет отпечаток запроса.
            val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0"
            val muid = ByteArray(16).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02X".format(it) }

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
                append("User-Agent: $ua\r\n")
                append("Accept-Language: en-US,en;q=0.9\r\n")
                append("Cookie: muid=$muid;\r\n")
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
                val serverSec = try {
                    dateHdr?.let {
                        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(it)!!.time / 1000L
                    }
                } catch (_: Exception) { null }
                throw HandshakeRejected(status, serverSec, "Edge WebSocket handshake failed: $response")
            }

            FileLogger.log("EdgeTTS: WebSocket соединение установлено")

            // Отправляем конфигурацию синтеза
            val configJson = """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"$FORMAT"}}}}"""
            sendText(output, "X-Timestamp:${now()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n$configJson\r\n")
            FileLogger.log("EdgeTTS: Конфигурация отправлена")

            // Формируем SSML
            // xml:lang — только «язык-РЕГИОН» (первые два сегмента имени голоса).
            // Раньше брали всё до последнего дефиса: у диалектных голосов
            // («zh-CN-liaoning-XiaobeiNeural») получалось невалидное «zh-CN-liaoning».
            val lang = voice.split('-').take(2).joinToString("-")
            val ssml = "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"$lang\"><voice name=\"$voice\"><prosody rate=\"$rate\" pitch=\"$pitch\" volume=\"$volume\">${escape(text)}</prosody></voice></speak>"

            FileLogger.log("EdgeTTS: Отправка SSML")
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
                        FileLogger.log("EdgeTTS: Текстовый фрейм: ${s.substring(0, minOf(100, s.length))}")
                        if (s.contains("Path:turn.end")) {
                            FileLogger.log("EdgeTTS: Получен turn.end")
                            break
                        }
                    }
                    2 -> {
                        if (frame.second.size >= 2) {
                            val h = ((frame.second[0].toInt() and 255) shl 8) or (frame.second[1].toInt() and 255)
                            if (h + 2 <= frame.second.size) {
                                val audioData = frame.second.size - h - 2
                                audio.write(frame.second, h + 2, audioData)
                                if (frameCount % 10 == 0) {
                                    FileLogger.log("EdgeTTS: Получено аудио: ${audio.size()} байт")
                                }
                            }
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
                error("Edge returned no audio for voice=$voice, text='${text.take(40)}' " +
                    "(если язык текста не совпадает с языком голоса — выберите голос ...MultilingualNeural)")
            }

            FileLogger.log("EdgeTTS: Синтез завершён успешно")
            return audio.toByteArray()
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ИСПРАВЛЕНО: правильный алгоритм GEC без потери точности
    private fun gec(): String {
        val unixSeconds = System.currentTimeMillis() / 1000L + clockSkewSec
        val windowsTicks = (unixSeconds + 11644473600L) - ((unixSeconds + 11644473600L) % 300L)
        val ticks100ns = windowsTicks * 10000000L
        val bytes = "$ticks100ns$TOKEN".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        return hash.joinToString("") { "%02X".format(it) }
    }

    // Как date_to_string() в edge-tts: JS-стиль даты.
    private fun now() = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }
        .format(Date())

    private fun escape(s: String) = s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

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