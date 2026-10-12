package com.tonbo2339.yomikura

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 公開版 (oss) の既定。R18 のサイトは、自分で使うと決めるまで出さない。更新にログインは要らない */
class EditionOssTest {
    @Test fun R18は設定に無ければ出さない() {
        assertTrue(NHData.nsfwOn(null))
        assertTrue(NHData.nsfwOn(JSONObject()))
    }

    @Test fun R18を使うと決めた設定は出す() {
        assertFalse(NHData.nsfwOn(JSONObject("""{"nsfw":false}""")))
        assertTrue(NHData.nsfwOn(JSONObject("""{"nsfw":true}""")))
    }

    @Test fun 通信は止めない_読み込み完了を待たずに注入する() {
        assertTrue(Edition.INJECT_AT_DOM_READY)
        assertFalse(Edition.UPDATE_NEEDS_LOGIN)
    }
}
