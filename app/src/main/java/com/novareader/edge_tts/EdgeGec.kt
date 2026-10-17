package com.novareader.edge_tts

/**
 * Токен Sec-MS-GEC версии 2 — порт `edge_tts/drm.py::generate_sec_ms_gec_v2` из
 * Python-пакета edge-tts 7.3.1.
 *
 * Формат: "<16 hex CS64>-<64 hex SHA-256>", считается от строки
 * "<время в 100-нс тиках Windows, округлённое вниз до 5 минут><TrustedClientToken>".
 * Старый токен (только SHA-256 в верхнем регистре, заголовок `Sec-MS-GEC-Version=1-…`) сервер
 * больше не принимает. Все операции — 32-битные с переполнением (Int), как `& 0xFFFFFFFF` в Python.
 *
 * Константы ниже перенесены из drm.py скриптом, вручную не править.
 */
internal object EdgeGec {
    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WIN_EPOCH = 11644473600L

    // --- Константы edge_tts/constants.py (7.3.1). При выходе новой версии пакета сверять в первую очередь их. ---
    const val TRUSTED_CLIENT_TOKEN = TOKEN
    const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    val CHROMIUM_MAJOR_VERSION: String = CHROMIUM_FULL_VERSION.substringBefore('.')
    /** Sec-MS-GEC-Version: «2-» + полная версия Chromium (раньше было «1-»). */
    val SEC_MS_GEC_VERSION: String = "2-$CHROMIUM_FULL_VERSION"
    const val BASE_URL = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
    const val USER_AGENT_FMT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/%s.0.0.0 Safari/537.36 Edg/%s.0.0.0"
    val USER_AGENT: String = USER_AGENT_FMT.format(CHROMIUM_MAJOR_VERSION, CHROMIUM_MAJOR_VERSION)

    /**
     * Поправка часов телефона относительно сервера, секунды (DRM.clock_skew_seconds).
     * Уточняется по заголовку Date из ответа 403.
     */
    @Volatile private var clockSkewSec: Long = 0L

    /** Текущий токен с учётом поправки часов (DRM.generate_sec_ms_gec). */
    fun currentToken(): String = token(System.currentTimeMillis() / 1000L + clockSkewSec)

    /**
     * Как DRM.handle_client_response_error: по Date из ответа сервера (RFC 2616) подправляет часы.
     * @return true, если поправка вычислена и стоит повторить запрос.
     */
    fun adjustClockFromServerDate(dateHeader: String?): Boolean {
        val server = parseHttpDate(dateHeader) ?: return false
        val local = System.currentTimeMillis() / 1000L
        clockSkewSec += server - (local + clockSkewSec)
        return true
    }

    fun skewSeconds(): Long = clockSkewSec

    private fun parseHttpDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            val f = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("GMT")
            f.parse(s.trim())!!.time / 1000L
        } catch (_: Exception) { null }
    }

    /** Случайный MUID (DRM.generate_muid): 32 hex в верхнем регистре. */
    fun muid(): String = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        .joinToString("") { "%02X".format(it) }

    private val SHA_INITIAL = intArrayOf(
        0x6A09E667.toInt(), 0xBB67AE85.toInt(), 0x3C6EF372.toInt(), 0xA54FF53A.toInt(),
        0x510E527F.toInt(), 0x9B05688C.toInt(), 0x1F83D9AB.toInt(), 0x5BE0CD19.toInt()
    )
    private val SHA_STATE = intArrayOf(
        0xFD9FBEA2.toInt(), 0x91243CE7.toInt(), 0xBE499094.toInt(), 0xCD2E1E0A.toInt(),
        0x6C006F49.toInt(), 0x04BD35AB.toInt(), 0xFEB0A7E4.toInt(), 0x03EFFBD2.toInt()
    )
    private val SHA_SCHEDULE = intArrayOf(
        0xEB24BF9E.toInt(), 0x1558691F.toInt(), 0xA0C09EE2.toInt(), 0x0CFA32BA.toInt(),
        0xA622EE8C.toInt(), 0xD1DD9491.toInt(), 0xC0DD25A0.toInt(), 0xF9E70AB6.toInt(),
        0xF0C31573.toInt(), 0x7058CE4E.toInt(), 0x7188E606.toInt(), 0x00E5AB33.toInt(),
        0xD258B060.toInt(), 0x72766564.toInt()
    )
    private val SHA_K = intArrayOf(
        0x428A2F98.toInt(), 0x71374491.toInt(), 0xB5C0FBCF.toInt(), 0xE9B5DBA5.toInt(),
        0x3956C25B.toInt(), 0x59F111F1.toInt(), 0x923F82A4.toInt(), 0xAB1C5ED5.toInt(),
        0xD807AA98.toInt(), 0x12835B01.toInt(), 0x243185BE.toInt(), 0x550C7DC3.toInt(),
        0x72BE5D74.toInt(), 0x80DEB1FE.toInt(), 0x9BDC06A7.toInt(), 0xC19BF174.toInt(),
        0xE49B69C1.toInt(), 0xEFBE4786.toInt(), 0x0FC19DC6.toInt(), 0x240CA1CC.toInt(),
        0x2DE92C6F.toInt(), 0x4A7484AA.toInt(), 0x5CB0A9DC.toInt(), 0x76F988DA.toInt(),
        0x983E5152.toInt(), 0xA831C66D.toInt(), 0xB00327C8.toInt(), 0xBF597FC7.toInt(),
        0xC6E00BF3.toInt(), 0xD5A79147.toInt(), 0x06CA6351.toInt(), 0x14292967.toInt(),
        0x27B70A85.toInt(), 0x2E1B2138.toInt(), 0x4D2C6DFC.toInt(), 0x53380D13.toInt(),
        0x650A7354.toInt(), 0x766A0ABB.toInt(), 0x81C2C92E.toInt(), 0x92722C85.toInt(),
        0xA2BFE8A1.toInt(), 0xA81A664B.toInt(), 0xC24B8B70.toInt(), 0xC76C51A3.toInt(),
        0xD192E819.toInt(), 0xD6990624.toInt(), 0xF40E3585.toInt(), 0x106AA070.toInt(),
        0x19A4C116.toInt(), 0x1E376C08.toInt(), 0x2748774C.toInt(), 0x34B0BCB5.toInt(),
        0x391C0CB3.toInt(), 0x4ED8AA4A.toInt(), 0x5B9CCA4F.toInt(), 0x682E6FF3.toInt(),
        0x748F82EE.toInt(), 0x78A5636F.toInt(), 0x84C87814.toInt(), 0x8CC70208.toInt(),
        0x90BEFFFA.toInt(), 0xA4506CEB.toInt(), 0xBEF9A3F7.toInt(), 0xC67178F2.toInt()
    )
    private val SHA_ADJ = 0xC9DC06A7.toInt()
    private val SIGMA0_ADJ = 0x059C0B80.toInt()
    private val SIGMA1_ADJ = 0x000B92C0.toInt()
    private val CS64_COEF = intArrayOf(
        0xE82E91B1.toInt(), 0x44DE24C6.toInt(), 0x3BEB65BC.toInt(), 0xC89C5933.toInt(),
        0x8D1EC211.toInt(), 0x314EF5D5.toInt(), 0xE92491B1.toInt(), 0xF0A5B9D5.toInt(),
        0x06064CF3.toInt(), 0xDA79FA60.toInt(), 0xBED20560.toInt(), 0x89F9C9F8.toInt(),
        0x35FA38C6.toInt(), 0xCB49E3C6.toInt()
    )
    private val CS64_OFFS = intArrayOf(
        0xB32700D0.toInt(), 0x88DFF51D.toInt(), 0x29514038.toInt(), 0xB328CBDF.toInt(),
        0xD405B4D5.toInt(), 0x26E55F3C.toInt(), 0x607801C6.toInt(), 0x82B2C1FB.toInt(),
        0xFA51A3DA.toInt(), 0x207CC464.toInt(), 0x3C3C74F3.toInt(), 0xD2D14B61.toInt(),
        0xBB04A2C1.toInt(), 0xC5626167.toInt()
    )
    private val CS64_FACTOR = 0x82893933.toInt()
    private val CS64_ADJ0 = 0xF2F90B1A.toInt()
    private val CS64_ADJ1 = 0x0B1A0000.toInt()


    private val SHA_K_BLOCK0: IntArray = SHA_K.copyOf().also { it[14] = SHA_ADJ }

    /** Значение заголовка/параметра Sec-MS-GEC для момента [unixSeconds] (с поправкой часов — на вызывающем). */
    fun token(unixSeconds: Long): String {
        var t = unixSeconds + WIN_EPOCH
        t -= Math.floorMod(t, 300L)
        val data = ((t * 10_000_000L).toString() + TOKEN).toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(data)
        val (c0, c1) = cs64Digest(data, digest)
        return hexLe(c0) + hexLe(c1) + "-" + digest.joinToString("") { "%02X".format(it) }
    }

    private fun hexLe(v: Int): String =
        (0..3).joinToString("") { "%02X".format((v ushr (8 * it)) and 0xFF) }

    private fun rotr(v: Int, n: Int) = Integer.rotateRight(v, n)
    private fun rol16(v: Int) = (v shl 16) or (v ushr 16)
    private fun sigma0(v: Int) = rotr(v, 7) xor rotr(v, 18) xor (v ushr 3)
    private fun sigma1(v: Int) = rotr(v, 17) xor rotr(v, 19) xor (v ushr 10)

    private fun be32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or
            ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)

    private fun le32(b: ByteArray, o: Int) =
        ((b[o + 3].toInt() and 255) shl 24) or ((b[o + 2].toInt() and 255) shl 16) or
            ((b[o + 1].toInt() and 255) shl 8) or (b[o].toInt() and 255)

    private fun expand(w: IntArray, from: Int) {
        for (i in from until 64) w[i] = sigma1(w[i - 2]) + w[i - 7] + sigma0(w[i - 15]) + w[i - 16]
    }

    private fun rounds(state: IntArray, w: IntArray, first: Int): IntArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        val k = if (first == 14) SHA_K_BLOCK0 else SHA_K
        for (i in first until 64) {
            val t1 = h + (rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)) + (g xor (e and (f xor g))) + k[i] + w[i]
            val t2 = (rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)) + ((a and b) or (c and (a or b)))
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    /** Начальное состояние по первым 7 байтам данных (первый блок содержит «скрытый» префикс). */
    private fun shaInit(d7: ByteArray): IntArray {
        val w = IntArray(64)
        w[14] = ((d7[0].toInt() and 255) shl 16) or ((d7[1].toInt() and 255) shl 8) or (d7[2].toInt() and 255)
        w[15] = be32(d7, 3)
        for (index in 16..29) {
            var v = SHA_SCHEDULE[index - 16]
            if (index - 2 >= 14) v += if (index == 16) sigma1(w[index - 2]) xor SIGMA1_ADJ else sigma1(w[index - 2])
            if (index - 7 >= 14) v += w[index - 7]
            if (index - 15 >= 14) v += if (index == 29) sigma0(w[index - 15]) xor SIGMA0_ADJ else sigma0(w[index - 15])
            w[index] = v
        }
        w[30] = sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] + SHA_ADJ - SHA_K[14]
        expand(w, 31)
        val working = rounds(SHA_STATE, w, 14)
        return IntArray(8) { SHA_INITIAL[it] + working[it] }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "Token salt must contain at least seven bytes" }
        val length = 57 + data.size
        val zeros = Math.floorMod(55 - length, 64)
        val tail = data.size - 7
        val padded = ByteArray(tail + 1 + zeros + 8)
        System.arraycopy(data, 7, padded, 0, tail)
        padded[tail] = 0x80.toByte()
        val bits = length.toLong() * 8
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()
        var state = shaInit(data.copyOfRange(0, 7))
        val w = IntArray(64)
        for (off in padded.indices step 64) {
            for (i in 0 until 16) w[i] = be32(padded, off + 4 * i)
            expand(w, 16)
            val working = rounds(state, w, 0)
            state = IntArray(8) { state[it] + working[it] }
        }
        val out = ByteArray(32)
        for (i in 0 until 8) for (j in 0 until 4) out[4 * i + j] = (state[i] ushr (24 - 8 * j)).toByte()
        return out
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<Int, Int> {
        val keyA = le32(digest, 0) or 1
        val keyB = le32(digest, 4) or 1
        val f0 = keyA * CS64_FACTOR
        val f1 = keyB * CS64_FACTOR
        // слова: 0x00 + data, дополнено нулями до чётного числа 32-битных LE-слов
        val src = ByteArray(1 + data.size).also { System.arraycopy(data, 0, it, 1, data.size) }
        var n = (src.size + 3) / 4
        if (n % 2 == 1) n++
        val buf = src.copyOf(n * 4)
        val total = CS64_COEF.size + n
        val terms = IntArray(total)
        val offs = IntArray(total)
        for (i in CS64_COEF.indices) {
            terms[i] = CS64_COEF[i] * (if (i % 2 == 0) f0 else f1)
            offs[i] = CS64_OFFS[i]
        }
        for (i in 0 until n) {
            val word = le32(buf, 4 * i)
            var term = word * (if (i % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (i % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
            if (i == 0) {
                term += CS64_ADJ0 * f0
                offset += CS64_ADJ1
            }
            terms[CS64_COEF.size + i] = term
            offs[CS64_COEF.size + i] = offset
        }
        var swapAcc = 0; var swapLast = 0; var state = 0; var acc = 0
        var i = 0
        while (i < total) {
            val x = terms[i]; val y = terms[i + 1]
            var t = rol16(offs[i] + x)
            var q = t * 0x76F50F34
            swapAcc += rol16(q) * 0xC3EFEA97.toInt() + q
            t = rol16(offs[i + 1] + y)
            q = t * 0x1881CE3C
            swapLast = rol16(q) * 0x35BD1EC9 + q
            swapAcc += swapLast

            state = state * keyA + x
            state = rol16(state) * 0xCF98B111.toInt()
            state = rol16(state) * 0x87085B9F.toInt()
            state = rol16(state) * 0x12CEB96D
            state = rol16(state) * 0x257E1D83
            acc += state
            t = state * keyB + y
            t = rol16(t) * 0xA27416F5.toInt()
            t = rol16(t) * 0xD38396FF.toInt()
            state = rol16(rol16(t) * 0x7C932B89) * 0xBFA49F69.toInt()
            acc += state
            i += 2
        }
        return Pair(swapLast xor state, swapAcc xor acc)
    }
}
