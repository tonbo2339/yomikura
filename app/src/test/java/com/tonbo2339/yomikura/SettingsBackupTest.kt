package com.tonbo2339.yomikura

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsBackupTest {
    @Test
    fun restoresOnlyWhenDataIsEmpty() {
        // 入れ直した直後 (保存データが空) なら、設定だけを戻し、リストは空から
        val d = JSONObject(SettingsBackup.restored("", """{"theme":"dark","welcomed":true}""")!!)
        assertEquals("dark", d.getJSONObject("settings").getString("theme"))
        assertEquals(0, d.getJSONArray("readLater").length())
        // 保存データがあるとき・写しが無いとき・写しが壊れているときは戻さない
        assertNull(SettingsBackup.restored("""{"readLater":[]}""", """{"theme":"dark"}"""))
        assertNull(SettingsBackup.restored("", null))
        assertNull(SettingsBackup.restored("", "not json"))
    }
}
