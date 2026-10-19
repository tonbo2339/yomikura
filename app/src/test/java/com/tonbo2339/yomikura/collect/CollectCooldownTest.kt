package com.tonbo2339.yomikura

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectCooldownTest {
    private val min = 60_000L

    // 前回の確認から 10 分は待つ。10 分たったら押せる
    @Test
    fun waitsTenMinutesAfterTheLastCheck() {
        val last = 1_000_000L
        assertEquals(10 * min, CollectCooldown.remainingMs(last, last))
        assertEquals(3 * min, CollectCooldown.remainingMs(last, last + 7 * min))
        assertEquals(0L, CollectCooldown.remainingMs(last, last + 10 * min))
        assertEquals(0L, CollectCooldown.remainingMs(last, last + 60 * min))
    }

    // 確認したことが無い・時計が戻って前回が未来になった、ときは待たない
    @Test
    fun noWaitWithoutAValidLastCheck() {
        assertEquals(0L, CollectCooldown.remainingMs(0L, 5_000L))
        assertEquals(0L, CollectCooldown.remainingMs(10 * min, 5 * min))
    }

    // 前回の確認は、終わった時刻と始めた時刻の新しい方 (途中で止まった確認も数え、「もう一度」で続けてアクセスしない)
    @Test
    fun lastCheckCountsRunsThatNeverFinished() {
        assertEquals(0L, CollectCooldown.lastOf(JSONObject()))
        assertEquals(5L, CollectCooldown.lastOf(JSONObject("""{"lastCollect":5}""")))
        assertEquals(9L, CollectCooldown.lastOf(JSONObject("""{"lastCollect":5,"collectStartAt":9}""")))
        assertEquals(9L, CollectCooldown.lastOf(JSONObject("""{"lastCollect":9,"collectStartAt":5}""")))
    }

    // 「あと N 分」は切り上げ
    @Test
    fun minutesAreRoundedUp() {
        assertEquals(0, CollectCooldown.remainingMinutes(0L))
        assertEquals(1, CollectCooldown.remainingMinutes(1L))
        assertEquals(1, CollectCooldown.remainingMinutes(min))
        assertEquals(7, CollectCooldown.remainingMinutes(6 * min + 1))
    }
}
