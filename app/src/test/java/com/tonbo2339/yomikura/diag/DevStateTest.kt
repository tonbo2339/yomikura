package com.tonbo2339.yomikura

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DevStateTest {
    @get:Rule val tmp = TemporaryFolder()
    private val min = 60_000L

    @Test
    fun restsListOnlyRecordedSitesLongestLeftFirst() {
        val now = 1_000 * min
        val rest = JSONObject().put("hameln", now - 10 * min).put("narou", now - 50 * min).put("kakuyomu", now - 120 * min)
        val list = DevState.rests(rest, now)
        assertEquals(listOf("hameln", "narou", "kakuyomu"), list.map { it.site }) // 残りの長い順
        assertEquals(50L, list[0].leftMin) // 自動の休止は 60 分
        assertEquals(10L, list[1].leftMin)
        assertTrue(list[0].resting && list[1].resting)
        assertFalse(list[2].resting) // もう明けている (残り 0)
        assertEquals(0L, list[2].leftMin)
    }

    @Test
    fun restsIgnoreMissingOrZeroRecords() {
        assertTrue(DevState.rests(null, 1L).isEmpty())
        assertTrue(DevState.rests(JSONObject().put("hameln", 0L), 1_000_000L).isEmpty())
    }

    @Test
    fun queueSummaryCountsByStatus() {
        assertEquals("なし", DevState.queueSummary(emptyList()))
        assertEquals("RUNNING=1 WAITING=2", DevState.queueSummary(listOf("RUNNING", "WAITING", "WAITING")))
    }

    @Test
    fun agoUsesTheLargestUnit() {
        assertEquals("5 分前", DevState.ago(5))
        assertEquals("2 時間前", DevState.ago(150))
        assertEquals("3 日前", DevState.ago(3 * 24 * 60 + 10))
    }

    @Test
    fun shotStoreKeepsOnlyTheNewestFiles() {
        val dir = tmp.newFolder("shots")
        for (t in 1L..5L) File(dir, ShotStore.nameOf(t * 1_000)).writeText("x")
        File(dir, "other.txt").writeText("keep") // 画像でないファイルは触らない
        ShotStore.trim(dir, max = 3)
        assertEquals(listOf("shot-3000.png", "shot-4000.png", "shot-5000.png"), dir.listFiles { f -> f.name.endsWith(".png") }!!.map { it.name }.sorted())
        assertTrue(File(dir, "other.txt").isFile)
    }

    @Test
    fun shotNameSortsByTime() {
        assertTrue(ShotStore.nameOf(1_000_000_000_000L) < ShotStore.nameOf(1_000_000_000_001L))
    }
}
