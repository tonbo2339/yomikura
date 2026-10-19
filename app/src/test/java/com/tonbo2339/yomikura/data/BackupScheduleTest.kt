package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupScheduleTest {
    private val h = BackupSchedule.HOUR_MS
    private fun due(auto: Boolean = true, busy: Boolean = false, ready: Boolean = true, now: Long, lastOk: Long, lastTry: Long, hours: Int = 12) =
        BackupSchedule.isDue(auto, busy, ready, now, lastOk, lastTry, hours)

    @Test
    fun neverSentIsDueRightAway() {
        assertTrue(due(now = 100 * h, lastOk = 0, lastTry = 0))
    }

    @Test
    fun waitsForTheIntervalAfterASuccess() {
        assertFalse(due(now = 11 * h, lastOk = 0 + 1, lastTry = 1)) // 12 時間たっていない
        assertTrue(due(now = 12 * h + 1, lastOk = 1, lastTry = 1))
        assertFalse(due(now = 23 * h, lastOk = 12 * h, lastTry = 12 * h, hours = 12))
        assertTrue(due(now = 24 * h, lastOk = 12 * h, lastTry = 12 * h, hours = 12))
        assertFalse(due(now = 30 * h, lastOk = 10 * h, lastTry = 10 * h, hours = 24)) // 間隔 24 時間なら、まだ
    }

    @Test
    fun aFailureIsRetriedAfterAnHourEvenIfTheIntervalHasPassed() {
        assertFalse(due(now = 100 * h + h / 2, lastOk = 1, lastTry = 100 * h)) // 失敗から 30 分
        assertTrue(due(now = 101 * h, lastOk = 1, lastTry = 100 * h))          // 失敗から 1 時間
    }

    @Test
    fun notDueWhenOffBusyOrNotReady() {
        assertFalse(due(auto = false, now = 100 * h, lastOk = 0, lastTry = 0))
        assertFalse(due(busy = true, now = 100 * h, lastOk = 0, lastTry = 0))
        assertFalse(due(ready = false, now = 100 * h, lastOk = 0, lastTry = 0))
    }

    @Test
    fun nextAtIsZeroUntilTheFirstSuccess() {
        assertEquals(0L, BackupSchedule.nextAt(0, 12))
        assertEquals(5 * h + 6 * h, BackupSchedule.nextAt(5 * h, 6))
    }

    @Test
    fun valuesOutsideTheChoicesFallBack() {
        assertEquals(12, BackupSchedule.hoursOrDefault(7))
        assertEquals(24, BackupSchedule.hoursOrDefault(24))
        assertEquals(BackupSchedule.DEFAULT_KEEP, BackupSchedule.keepOrDefault(-1))
        assertEquals(BackupSchedule.DEFAULT_KEEP, BackupSchedule.keepOrDefault(BackupSchedule.KEEP_MAX + 1))
        assertEquals(1, BackupSchedule.keepOrDefault(1))
        assertEquals(0, BackupSchedule.keepOrDefault(0)) // 無制限は、以前の設定として残っていれば通す
        assertEquals(BackupSchedule.DEFAULT_KEEP, BackupSchedule.keepOrDefault(0, allowUnlimited = false))
    }

    @Test
    fun staleAlertWaitsThreeDaysAndNotRepeated() {
        val d = 24 * h
        fun alert(auto: Boolean = true, now: Long, lastOk: Long, lastAlert: Long = 0) = BackupSchedule.shouldAlertStale(auto, now, lastOk, lastAlert)
        assertFalse(alert(now = 10 * d, lastOk = 8 * d)) // 2 日: まだ
        assertTrue(alert(now = 10 * d, lastOk = 7 * d)) // 3 日たった
        assertFalse(alert(now = 10 * d, lastOk = 7 * d, lastAlert = 9 * d)) // 昨日通知した
        assertTrue(alert(now = 13 * d, lastOk = 7 * d, lastAlert = 10 * d)) // 前回の通知から 3 日
        assertFalse(alert(auto = false, now = 10 * d, lastOk = 1 * d)) // 自動がオフなら出さない
        assertFalse(alert(now = 10 * d, lastOk = 0)) // 一度も成功していない間は出さない
    }

    @Test
    fun delayUntilDueCombinesIntervalAndRetry() {
        assertNull(BackupSchedule.delayUntilDue(false, 100 * h, 0, 0, 12))
        assertEquals(0L, BackupSchedule.delayUntilDue(true, 100 * h, 0, 0, 12)) // まだ一度も成功していない
        assertEquals(4 * h, BackupSchedule.delayUntilDue(true, 100 * h, 92 * h, 92 * h, 12)) // 12 時間のうち 8 時間たった
        assertEquals(h - 10 * 60_000L, BackupSchedule.delayUntilDue(true, 100 * h, 80 * h, 100 * h - 10 * 60_000L, 12)) // 間隔は過ぎたが、10 分前に失敗した → 1 時間のやり直しまで
        assertEquals(0L, BackupSchedule.delayUntilDue(true, 100 * h, 80 * h, 90 * h, 12))
    }
}
