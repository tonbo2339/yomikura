package com.tonbo2339.yomikura

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageSpaceTest {
    private val mb = 1024L * 1024

    @Test
    fun asksWhenFreeSpaceIsBelowTheLowLimit() {
        assertTrue(StorageSpace.shouldAsk(100 * mb, 1 * mb)) // 500 MB 未満
        assertFalse(StorageSpace.shouldAsk(2000 * mb, 1 * mb))
    }

    @Test
    fun asksWhenFreeSpaceIsLessThanTwiceTheEstimate() {
        assertTrue(StorageSpace.shouldAsk(1000 * mb, 600 * mb)) // 空きは 500 MB を超えるが、見込みの 2 倍 (1200 MB) に足りない
        assertFalse(StorageSpace.shouldAsk(1300 * mb, 600 * mb))
    }

    @Test
    fun doesNotAskWhenFreeSpaceIsUnknownOrEstimateIsNegative() {
        assertFalse(StorageSpace.shouldAsk(-1L, 100 * mb))
        assertFalse(StorageSpace.shouldAsk(2000 * mb, -5L))
    }

    @Test
    fun canStartOnlyWithEnoughSpace() {
        assertFalse(StorageSpace.canStart(10 * mb))
        assertFalse(StorageSpace.canStart(StorageSpace.MIN_BYTES - 1))
        assertTrue(StorageSpace.canStart(StorageSpace.MIN_BYTES))
        assertTrue(StorageSpace.canStart(-1L)) // 分からないときは始める
    }
}
