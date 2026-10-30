package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class DevLogFormatTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun formatKeepsTheFullUrlOnOneLine() {
        val line = DevLogFormat.format(0L, "net", 'I', "native GET 403 https://syosetu.org/?mode=ss_detail&nid=123", "12ms cookie=sent\nServer=cloudflare", utc)
        val p = line.split('\t')
        assertEquals(5, p.size)
        assertEquals("1970-01-01 00:00:00.000", p[0])
        assertEquals(listOf("net", "I", "native GET 403 https://syosetu.org/?mode=ss_detail&nid=123"), p.subList(1, 4)) // URL は省かない
        assertEquals("12ms cookie=sent Server=cloudflare", p[4]) // 改行は空白に
    }

    @Test
    fun parseRoundTrips() {
        val line = DevLogFormat.format(1_000_000_123L, "app", 'W', "start", "version=1.1.12 pid=42", utc)
        assertEquals(DevLogFormat.Entry(1_000_000_123L, "app", 'W', "start", "version=1.1.12 pid=42"), DevLogFormat.parse(line, utc))
    }

    @Test
    fun parseRejectsBrokenLines() {
        assertNull(DevLogFormat.parse("", utc))
        assertNull(DevLogFormat.parse("not a log line", utc))
        assertNull(DevLogFormat.parse("1970-01-01 00:00:00.000\tnet\tXY\tmsg", utc)) // レベルが 1 文字でない
        assertNull(DevLogFormat.parse("1970-01-01\tnet\tI\tmsg", utc)) // 時刻の形が違う
    }

    @Test
    fun fieldsOfSkipsNullValues() {
        assertEquals("a=1 c=x", DevLogFormat.fieldsOf("a" to 1, "b" to null, "c" to "x"))
        assertEquals("", DevLogFormat.fieldsOf())
    }

    @Test
    fun filterByCategoryAndQuery() {
        val lines = listOf(
            DevLogFormat.format(1L, "net", 'I', "native GET 200 https://syosetu.org/", "", utc),
            DevLogFormat.format(2L, "error", 'W', "Yomikura.hameln18 stopped: 403", "", utc),
            DevLogFormat.format(3L, "net", 'I', "web:view GET - https://kakuyomu.jp/", "navigate", utc),
        )
        assertEquals(3, DevLogFormat.filter(lines, null, "").size)
        assertEquals(3, DevLogFormat.filter(lines, emptySet(), "").size) // 空 = 全部
        assertEquals(listOf(lines[0], lines[2]), DevLogFormat.filter(lines, setOf("net"), ""))
        assertEquals(listOf(lines[2]), DevLogFormat.filter(lines, setOf("net"), "KAKUYOMU")) // 大文字・小文字を区別しない
        assertEquals(listOf(lines[1]), DevLogFormat.filter(lines, null, "403"))
        assertTrue(DevLogFormat.filter(lines, setOf("app"), "").isEmpty())
    }

    @Test
    fun dropOlderThanKeepsNewAndUnreadableLines() {
        val lines = listOf(
            DevLogFormat.format(1_000L, "net", 'I', "old", "", utc),
            "broken line",
            DevLogFormat.format(5_000L, "net", 'I', "new", "", utc),
        )
        assertEquals(listOf(lines[1], lines[2]), DevLogFormat.dropOlderThan(lines, 2_000L, utc))
        assertEquals(lines, DevLogFormat.dropOlderThan(lines, 0L, utc))
    }

    @Test
    fun legacyNetLogLineIsConverted() {
        val legacy = "2026-10-28 07:01:03.698\tnative\tGET\t403\thttps://syosetu.org/?mode=ss_detail&nid=1\t150ms cookie=sent Server=cloudflare"
        val converted = DevLogFormat.fromLegacyNetLog(legacy)!!
        val p = converted.split('\t')
        assertEquals(listOf("2026-10-28 07:01:03.698", "net", "I", "native GET 403 https://syosetu.org/?mode=ss_detail&nid=1", "150ms cookie=sent Server=cloudflare"), p)
        assertEquals("net", DevLogFormat.parse(converted)?.category) // 新しい形として読める
        assertNull(DevLogFormat.fromLegacyNetLog("too\tshort"))
    }
}
