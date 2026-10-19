package com.tonbo2339.yomikura

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuItemsTest {
    // 未設定なら初期値: 診断だけオフ、ほかはオン
    @Test
    fun usesDefaultsWhenNothingIsChosen() {
        val st = JSONObject()
        assertFalse(MenuItems.on(st, MenuItems.DIAGNOSE))
        assertTrue(MenuItems.on(st, MenuItems.READ_ALOUD))
        assertTrue(MenuItems.on(st, MenuItems.OPEN_BROWSER))
    }

    // 選んだ値が初期値より優先される
    @Test
    fun chosenValuesWin() {
        val st = JSONObject("""{"menuItems":{"diagnose":true,"readAloud":false}}""")
        assertTrue(MenuItems.on(st, MenuItems.DIAGNOSE))
        assertFalse(MenuItems.on(st, MenuItems.READ_ALOUD))
        assertTrue(MenuItems.on(st, MenuItems.AUTO_SCROLL))
    }

    // 以前の menuShare = false は、共有の 3 項目が未設定のときだけ引き継ぐ
    @Test
    fun carriesOverTheOldShareSwitch() {
        val old = JSONObject("""{"menuShare":false}""")
        assertFalse(MenuItems.on(old, MenuItems.SHARE))
        assertFalse(MenuItems.on(old, MenuItems.COPY_URL))
        assertFalse(MenuItems.on(old, MenuItems.OPEN_BROWSER))
        assertTrue(MenuItems.on(old, MenuItems.FIND))
        val chosen = JSONObject("""{"menuShare":false,"menuItems":{"share":true}}""")
        assertTrue(MenuItems.on(chosen, MenuItems.SHARE))
        assertFalse(MenuItems.on(chosen, MenuItems.COPY_URL))
    }

    // 知らない項目は出す。設定画面への一覧は全項目を並びどおりに
    @Test
    fun unknownKeysAreShownAndListIsComplete() {
        assertTrue(MenuItems.on(JSONObject(), "something"))
        val list = MenuItems.toJson(JSONObject("""{"menuItems":{"find":false}}"""))
        assertEquals(MenuItems.ALL.size, list.length())
        assertEquals(MenuItems.LATER, list.getJSONObject(0).getString("key"))
        val find = (0 until list.length()).map { list.getJSONObject(it) }.first { it.getString("key") == "find" }
        assertFalse(find.getBoolean("on"))
        assertTrue(find.getBoolean("default"))
    }
}
