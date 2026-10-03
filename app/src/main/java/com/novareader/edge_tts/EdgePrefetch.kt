package com.novareader.edge_tts

import java.net.InetAddress
import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory

/**
 * Предзагрузка озвучки следующих фраз (аналог prefetch в десктопной версии).
 *
 * Читалка передаёт тексты следующих предложений в параметрах TextToSpeech.speak()
 * (ключ "nova_prefetch", JSON-массив строк). Пока играет текущая фраза, эти
 * фразы синтезируются в фоне и кладутся в кэш; когда система запросит их озвучку,
 * PCM уже готов и задержки нет.
 *
 * Правила:
 *  • ключ кэша = (голос, скорость, ТОЧНЫЙ текст) — читалка шлёт ровно тот текст,
 *    который потом пойдёт в speak(), иначе заготовка не пригодится;
 *  • живая фраза в приоритете: планируем заготовки только ПОСЛЕ того, как сетевой
 *    запрос текущей фразы завершён (на медленном канале они иначе делили бы его);
 *  • параллельно не больше 2 запросов, кэш ограничен по числу записей и времени;
 *  • устаревшие, ещё не начатые заготовки отменяются (пользователь перепрыгнул).
 */
class EdgePrefetch(private val client: EdgeTTSClient) {

    private data class Key(val voice: String, val rate: String, val text: String)
    private class Entry(val future: Future<Pair<ByteArray, Int>>, val ts: Long)

    private val pool: ExecutorService = Executors.newFixedThreadPool(2, ThreadFactory { r ->
        Thread(r, "nova-edge-prefetch").apply { isDaemon = true }
    })
    private val map = LinkedHashMap<Key, Entry>()

    companion object {
        private const val MAX_ENTRIES = 16
        private const val TTL_MS = 5 * 60 * 1000L
        private const val ATTEMPTS = 2
    }

    /** Готовая или выполняющаяся заготовка для (голос, скорость, текст) — либо null. */
    @Synchronized
    fun take(voice: String, rate: String, text: String): Future<Pair<ByteArray, Int>>? {
        val e = map[Key(voice, rate, text)] ?: return null
        if (e.future.isCancelled) { map.remove(Key(voice, rate, text)); return null }
        return e.future
    }

    /** Удалить запись (например, если заготовка завершилась ошибкой). */
    @Synchronized
    fun drop(voice: String, rate: String, text: String) {
        map.remove(Key(voice, rate, text))
    }

    /** Поставить в фон синтез texts (по порядку чтения). Уже имеющиеся пропускаем. */
    @Synchronized
    fun schedule(voice: String, rate: String, texts: List<String>) {
        val keys = texts.filter { it.isNotBlank() }.take(MAX_ENTRIES / 2 + 2)
            .map { Key(voice, rate, it) }
        val wanted = keys.toSet()
        // Отменяем ещё не начатые заготовки, которые больше не нужны
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val (k, e) = it.next()
            if (k !in wanted && !e.future.isDone) {
                e.future.cancel(false)   // false: уже идущий запрос не прерываем
                it.remove()
            }
        }
        for (k in keys) {
            if (map.containsKey(k)) continue
            val f = pool.submit(Callable { synth(k) })
            map[k] = Entry(f, System.currentTimeMillis())
        }
        trim()
    }

    private fun synth(k: Key): Pair<ByteArray, Int> {
        var last: Throwable? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                val t0 = System.currentTimeMillis()
                val mp3 = client.synthesize(text = k.text, voice = k.voice, rate = k.rate)
                val pcm = Mp3Decoder.decode(mp3)
                FileLogger.log("Prefetch: готово за ${System.currentTimeMillis() - t0} мс: '${k.text.take(40)}'")
                return pcm
            } catch (e: Throwable) {
                last = e
                FileLogger.log("Prefetch: попытка ${attempt + 1}/$ATTEMPTS не удалась: ${e.javaClass.simpleName}")
                try { Thread.sleep(400L * (attempt + 1)) } catch (_: InterruptedException) { throw e }
            }
        }
        throw last ?: IllegalStateException("prefetch failed")
    }

    private fun trim() {
        val now = System.currentTimeMillis()
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (e.future.isDone && now - e.ts > TTL_MS) it.remove()
        }
        while (map.size > MAX_ENTRIES) {
            val first = map.entries.iterator()
            if (!first.hasNext()) break
            val e = first.next()
            if (!e.value.future.isDone) e.value.future.cancel(false)
            first.remove()
        }
    }

    /** Прогрев: DNS + TLS-рукопожатие, чтобы первая фраза не платила за «холодный» старт. */
    fun warmup(host: String = "speech.platform.bing.com") {
        Thread({
            try {
                InetAddress.getAllByName(host)
                val s = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                    .createSocket(host, 443) as javax.net.ssl.SSLSocket
                s.soTimeout = 8000
                s.startHandshake()
                s.close()
                FileLogger.log("Warmup: DNS+TLS готовы")
            } catch (e: Throwable) {
                FileLogger.log("Warmup не удался: ${e.javaClass.simpleName}")
            }
        }, "nova-edge-warmup").apply { isDaemon = true }.start()
    }
}
