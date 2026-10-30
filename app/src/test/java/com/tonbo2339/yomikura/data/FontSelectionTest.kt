package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FontSelectionTest {
    @Test
    fun idOfReturnsTheIdOnlyForCustomFonts() {
        assertEquals("0123456789ab", FontSelection.idOf("custom:0123456789ab"))
        for (v in listOf("site", "gothic", "mincho", "", null, "custom:", "custom:XYZ", "custom:0123456789AB", "custom:0123456789abc", "custom:../../x", "xcustom:0123456789ab")) {
            assertNull(v, FontSelection.idOf(v))
        }
    }
}
