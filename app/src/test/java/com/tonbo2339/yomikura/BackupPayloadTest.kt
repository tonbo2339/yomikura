package com.tonbo2339.yomikura

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupPayloadTest {
    private val data = JSONObject("""{
        "readLater": [{"key": "narou:n1"}],
        "progress": {"narou:n1": {"y": 0.5}},
        "settings": {"theme": "dark"},
        "collectLog": {"x": 1},
        "history": [
            {"key": "narou:n1", "site": "narou", "id": "n1", "title": "新しい", "appAt": 300, "ep": "3", "y": 0.4},
            {"key": "narou18:n9", "site": "narou18", "sub": "noc", "id": "n9", "title": "R18", "appAt": 250},
            {"key": "kakuyomu:9", "site": "kakuyomu", "id": "9", "title": "少し前", "appAt": 200},
            {"key": "hameln:5", "site": "hameln", "id": "5", "title": "送った", "appAt": 100},
            {"key": "hameln:6", "site": "hameln", "id": "6", "title": "公式のみ", "at": -1, "siteAt": 999}
        ]
    }""")

    @Test
    fun snapshotsHaveTheExportShape() {
        val s = JSONObject(BackupPayload.snapshot(data, "settings", "any-app", "9.9", 0))
        assertEquals("any-app", s.getString("app")) // 形式名は渡した名前 (版ごとに違う)
        assertEquals(1, s.getInt("format"))
        assertEquals("9.9", s.getString("appVersion"))
        assertEquals("1970-01-01T00:00:00Z", s.getString("exportedAt"))
        val p = s.getJSONObject("parts")
        assertEquals(listOf("settings", "mute"), p.keys().asSequence().toList().sorted().reversed())
        assertEquals("dark", p.getJSONObject("settings").getString("theme"))
        assertEquals(0, p.getJSONObject("mute").length()) // 無い項目は空
        val d = JSONObject(BackupPayload.snapshot(data, "data", "x", "", 0)).getJSONObject("parts")
        assertEquals(1, d.getJSONArray("list").length()) // 保存データの readLater
        assertEquals(1, d.getJSONObject("progress").length())
        assert(!d.toString().contains("collectLog")) // 一時的なものは入れない
        // リストが無いときは空の配列
        assertEquals(0, JSONObject(BackupPayload.snapshot(JSONObject(), "data", "x", "", 0)).getJSONObject("parts").getJSONArray("list").length())
    }

    @Test
    fun historyIsNewerThanSinceInOrderWithoutPositions() {
        val h: JSONArray = BackupPayload.history(data, 100)
        val got = (0 until h.length()).map { h.getJSONObject(it) }
        assertEquals(listOf("少し前", "R18", "新しい"), got.map { it.getString("title") }) // 古い順。送った分と公式のみは入らない
        assertEquals("noc", got[1].getString("sub"))
        assertEquals(false, got[0].has("sub"))
        assertEquals(listOf("at", "id", "site", "title"), got[2].keys().asSequence().toList().sorted()) // 読んだ位置 (ep・y) は入れない
        assertEquals(0, BackupPayload.history(JSONObject(), 0).length())
    }
}
