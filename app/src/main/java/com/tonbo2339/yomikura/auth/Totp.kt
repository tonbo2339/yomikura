package com.tonbo2339.yomikura

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 認証アプリと同じ TOTP (RFC 6238: HMAC-SHA1・6 桁・30 秒) の計算。Android に依存しない (単体テストで動かせる)。
 * 秘密鍵 (Base32) の保存は [TotpVault]、JS への渡し方は [TotpBridge]。
 */
object Totp {
    const val PERIOD_SEC = 30L
    private const val DIGITS = 6
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** 貼り付けた鍵の空白・ハイフン・末尾の = を取り除き、大文字にそろえる。Base32 として読めなければ null。 */
    fun normalize(raw: String?): String? {
        val k = raw.orEmpty().filterNot { it.isWhitespace() || it == '-' }.trimEnd('=').uppercase()
        return if (k.length >= 16 && k.all { it in ALPHABET }) k else null
    }

    /** [unixSec] の時刻の 6 桁。鍵が Base32 として読めなければ null。 */
    fun code(secretBase32: String, unixSec: Long): String? {
        val key = decode(normalize(secretBase32) ?: return null)
        val counter = unixSec / PERIOD_SEC
        val msg = ByteArray(8) { i -> (counter shr (56 - 8 * i)).toByte() }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val h = mac.doFinal(msg)
        val o = h[h.size - 1].toInt() and 0x0f
        val bin = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or
            ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        return (bin % 1_000_000).toString().padStart(DIGITS, '0')
    }

    /** 今のコードが切り替わるまでの秒数 (1〜30)。 */
    fun secondsLeft(unixSec: Long): Long = PERIOD_SEC - unixSec % PERIOD_SEC

    private fun decode(b32: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buf = 0
        var bits = 0
        for (c in b32) {
            buf = (buf shl 5) or ALPHABET.indexOf(c)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buf shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}
