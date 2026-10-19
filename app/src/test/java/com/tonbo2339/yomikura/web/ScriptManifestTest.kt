package com.tonbo2339.yomikura

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class ScriptManifestTest {
    private val manifest = JSONObject("""{"a":["x.js","y.js"],"b":["@a","z.js"],"loop":["@loop"]}""")

    @Test fun `@名前 はそのグループを順番どおりに展開する`() {
        assertEquals(listOf("x.js", "y.js", "z.js"), ScriptManifest.resolve(manifest, "b"))
    }

    @Test fun `グループが無い・展開が終わらないときは例外`() {
        assertThrows(IllegalArgumentException::class.java) { ScriptManifest.resolve(manifest, "none") }
        assertThrows(IllegalArgumentException::class.java) { ScriptManifest.resolve(manifest, "loop") }
    }

    @Test fun `HTML の scripts の指示を script タグの並びに置き換える`() {
        val html = "<head><!--#scripts a--></head>"
        assertEquals("<head><script src=\"js/x.js\"></script>\n<script src=\"js/y.js\"></script></head>", ScriptManifest.expand(html, manifest))
    }

    @Test fun `実物の scripts_json の全グループが展開でき、ファイルがある`() {
        val dir = File("src/main/assets/js")
        val real = JSONObject(File(dir, "scripts.json").readText())
        for (group in real.keys().asSequence().filter { !it.startsWith("_") }) {
            for (f in ScriptManifest.resolve(real, group)) assertEquals("$group: $f", true, File(dir, f).isFile)
        }
    }
}
