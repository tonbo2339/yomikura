package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Test

class AdultGateTest {
    @Test fun 年齢の確認に答えたかはCookieのover18で分かる() {
        assertEquals(true, AdultGate.answered("ETURAN=a; over18=on; uu=b"))
        assertEquals(true, AdultGate.answered("over18=yes"))
        assertEquals(true, AdultGate.answered("over18=off"))
        assertEquals(false, AdultGate.answered("ETURAN=a; xover18=on; uu=b"))
        assertEquals(false, AdultGate.answered(""))
        assertEquals(false, AdultGate.answered(null))
    }
}
