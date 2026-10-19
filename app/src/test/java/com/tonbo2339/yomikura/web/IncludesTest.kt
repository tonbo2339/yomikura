package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncludesTest {
    private fun files(vararg f: Pair<String, String>): (String) -> String? = f.toMap().let { m -> { name -> m[name] } }

    @Test
    fun replacesWithFileContents() {
        val read = files("a.js" to "A", "settings/page-b.html" to "<b>B</b>")
        assertEquals("1 A 2 <b>B</b> 3", Includes.expand("1 <!--#include a.js--> 2 <!--#include settings/page-b.html--> 3", read))
    }

    @Test
    fun missingFileBecomesEmpty() {
        assertEquals("[]", Includes.expand("[<!--#include none.js-->]", files()))
        assertEquals("[]", Includes.expand("[<!--#include x.js-->]", { throw java.io.FileNotFoundException() })) // 読めなかった (例外) も空
    }

    @Test
    fun includesInsideIncludedFilesAreExpanded() {
        val read = files("main.html" to "M(<!--#include inner.html-->)", "inner.html" to "I")
        assertEquals("M(I)", Includes.expand("<!--#include main.html-->", read))
    }

    @Test
    fun depthIsLimited() {
        val read = files("loop.html" to "x<!--#include loop.html-->")
        val out = Includes.expand("<!--#include loop.html-->", read)
        assertEquals("x".repeat(Includes.MAX_DEPTH), out) // 自分を読む入れ子でも止まる。超えた分は空
    }

    @Test
    fun namesCannotLeaveTheWebFolder() {
        val read = files("../secret.txt" to "SECRET", "/etc/passwd" to "SECRET", "a//b" to "SECRET", "dir/" to "SECRET", "ok.js" to "ok")
        assertEquals("", Includes.expand("<!--#include ../secret.txt-->", read))
        assertEquals("", Includes.expand("<!--#include /etc/passwd-->", read))
        assertEquals("", Includes.expand("<!--#include a//b-->", read))
        assertEquals("", Includes.expand("<!--#include dir/-->", read))
        assertEquals("ok", Includes.expand("<!--#include ok.js-->", read))
        assertFalse(Includes.isValidName("a/../b"))
        assertTrue(Includes.isValidName("settings/page-list.html"))
    }

    @Test
    fun specialCharactersInContentsAreKeptAsIs() {
        val read = files("c.js" to "var a = '\$1 \\\\ \$&';")
        assertEquals("var a = '\$1 \\\\ \$&';", Includes.expand("<!--#include c.js-->", read))
    }
}
