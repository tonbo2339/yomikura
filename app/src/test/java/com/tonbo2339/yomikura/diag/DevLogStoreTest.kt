package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

class DevLogStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(ms: Long, msg: String) = DevLogFormat.format(ms, "net", 'I', msg, "", utc)

    @Test
    fun appendRotatesToOldWhenTooBig() {
        val dir = tmp.root
        DevLogStore.append(dir, "first line", maxBytes = 5) // 追記の前は空 (0 バイト) なので回さない
        DevLogStore.append(dir, "second line", maxBytes = 5) // 11 バイトで 5 を超えている: 先の分を OLD に回してから追記
        assertEquals(listOf("first line", "second line"), DevLogStore.lines(dir)) // 古い順 (OLD → いまの分)
        assertTrue(File(dir, DevLogStore.OLD).isFile)
        DevLogStore.append(dir, "third line", maxBytes = 5) // さらに回すと、いちばん古い OLD は捨てる
        assertEquals(listOf("second line", "third line"), DevLogStore.lines(dir))
    }

    @Test
    fun appendDoesNotRotateWhileSmall() {
        val dir = tmp.root
        repeat(3) { DevLogStore.append(dir, "line $it", maxBytes = 1_000) }
        assertEquals(listOf("line 0", "line 1", "line 2"), DevLogStore.lines(dir))
        assertFalse(File(dir, DevLogStore.OLD).exists())
    }

    @Test
    fun sizeAndClear() {
        val dir = tmp.root
        DevLogStore.append(dir, "abc", maxBytes = 1_000)
        assertEquals(4L, DevLogStore.sizeBytes(dir)) // "abc\n"
        DevLogStore.clear(dir)
        assertEquals(0L, DevLogStore.sizeBytes(dir))
        assertTrue(DevLogStore.lines(dir).isEmpty())
    }

    @Test
    fun dropOlderThanRewritesOnlyWhatChanges() {
        val dir = tmp.root
        val old = at(1_000L, "old")
        val fresh = at(9_000L, "fresh")
        File(dir, DevLogStore.FILE).writeText("$old\n$fresh\n")
        DevLogStore.dropOlderThan(dir, cutoff = 5_000L, zone = utc)
        assertEquals(listOf(fresh), DevLogStore.lines(dir))
        val f = File(dir, DevLogStore.FILE)
        val stamp = f.lastModified()
        DevLogStore.dropOlderThan(dir, cutoff = 5_000L, zone = utc) // もう古い行は無い: 書き直さない
        assertEquals(stamp, f.lastModified())
        DevLogStore.dropOlderThan(dir, cutoff = 99_000L, zone = utc) // 全部古い: 空にする
        assertTrue(DevLogStore.lines(dir).isEmpty())
    }

    @Test
    fun migrateLegacyPutsOldRecordFirstAndRemovesTheOriginals() {
        val dir = tmp.root
        File(dir, "netlog.old").writeText("2026-10-27 10:00:00.000\tnative\tGET\t200\thttps://a.example/\t5ms\n")
        File(dir, "netlog.log").writeText("2026-10-27 11:00:00.000\tweb:view\tGET\t-\thttps://b.example/\tnavigate\nbroken\n")
        File(dir, DevLogStore.FILE).writeText("already\n")
        DevLogStore.migrateLegacy(dir)
        val lines = DevLogStore.lines(dir)
        assertEquals(3, lines.size) // 壊れた行は捨てる
        assertEquals("net", lines[0].split('\t')[1])
        assertTrue(lines[0].contains("https://a.example/"))
        assertTrue(lines[1].contains("https://b.example/"))
        assertEquals("already", lines[2]) // いまのログの分は後ろ
        assertFalse(File(dir, "netlog.old").exists())
        assertFalse(File(dir, "netlog.log").exists())
        DevLogStore.migrateLegacy(dir) // 2 回目は何もしない
        assertEquals(3, DevLogStore.lines(dir).size)
    }
}
