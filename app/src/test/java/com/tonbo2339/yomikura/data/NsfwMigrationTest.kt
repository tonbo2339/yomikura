package com.tonbo2339.yomikura

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 初期値が違う旧版から入れ替えた保存データの引き継ぎ (NHData.migrateNsfwDefault)。どの版でも同じ変換 */
class NsfwMigrationTest {
    @Test fun 設定があって_nsfwが未設定ならオフで引き継ぐ() {
        val o = JSONObject("""{"settings":{"theme":"auto"},"readLater":[]}""")
        assertTrue(NHData.migrateNsfwDefault(o))
        assertFalse(o.getJSONObject("settings").getBoolean("nsfw"))
        assertEquals(1, o.getInt("nsfwMigrated"))
    }

    @Test fun nsfwを決めている設定は変えない() {
        val on = JSONObject("""{"settings":{"nsfw":true}}""")
        NHData.migrateNsfwDefault(on)
        assertTrue(on.getJSONObject("settings").getBoolean("nsfw"))
        val off = JSONObject("""{"settings":{"nsfw":false}}""")
        NHData.migrateNsfwDefault(off)
        assertFalse(off.getJSONObject("settings").getBoolean("nsfw"))
    }

    @Test fun 新規のデータは触らず_印だけ付ける() {
        val o = JSONObject()
        assertTrue(NHData.migrateNsfwDefault(o))
        assertFalse(o.has("settings")) // 設定は作らない = 初期値のまま
        assertEquals(1, o.getInt("nsfwMigrated"))
    }

    @Test fun 一度だけ行う() {
        val o = JSONObject("""{"settings":{}}""")
        assertTrue(NHData.migrateNsfwDefault(o))
        o.getJSONObject("settings").remove("nsfw") // あとで未設定に戻っても、もう引き継ぎはしない
        assertFalse(NHData.migrateNsfwDefault(o))
        assertFalse(o.getJSONObject("settings").has("nsfw"))
    }
}
