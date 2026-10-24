package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TotpTest {
    // RFC 6238 付録 B の SHA-1 の鍵 "12345678901234567890" の Base32
    private val rfcKey = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test fun `RFC 6238 の値の下 6 桁と一致する`() {
        assertEquals("287082", Totp.code(rfcKey, 59))
        assertEquals("081804", Totp.code(rfcKey, 1111111109))
        assertEquals("050471", Totp.code(rfcKey, 1111111111))
        assertEquals("005924", Totp.code(rfcKey, 1234567890))
        assertEquals("279037", Totp.code(rfcKey, 2000000000))
    }

    @Test fun `同じ 30 秒の中では同じコード`() {
        assertEquals(Totp.code(rfcKey, 60), Totp.code(rfcKey, 89))
        assertEquals(false, Totp.code(rfcKey, 89) == Totp.code(rfcKey, 90))
    }

    @Test fun `貼り付けた鍵の空白・小文字・ハイフン・イコールを許す`() {
        assertEquals("287082", Totp.code("gezd gnbv-gy3t qojq gezd gnbv gy3t qojq====", 59))
    }

    @Test fun `Base32 として読めない鍵と短すぎる鍵は受け付けない`() {
        assertNull(Totp.normalize(null))
        assertNull(Totp.normalize(""))
        assertNull(Totp.normalize("GEZDGNBV"))
        assertNull(Totp.normalize("0189GEZDGNBVGY3TQOJQ"))
        assertNull(Totp.code("not a key", 59))
    }

    @Test fun `切り替わるまでの秒数は 1 から 30`() {
        assertEquals(30L, Totp.secondsLeft(60))
        assertEquals(1L, Totp.secondsLeft(89))
        assertEquals(10L, Totp.secondsLeft(110))
    }
}
