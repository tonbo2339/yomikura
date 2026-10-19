package com.tonbo2339.yomikura

/**
 * サイトへのアクセスのマナー (Kotlin 側)。続けて取りに行くときは、1 件ごとに 1.5〜3 秒あける。
 * JS 側 (core/fetch.js の NH.pause) と同じ値。設定では変えられない (これより短くしない)。Android に依存しない (単体テストで動かせる)。
 */
object Politeness {
    const val MIN_MS = 1500L
    const val JITTER_MS = 1500L

    /** 次のアクセスまで待つ長さ。random は 0 以上 1 未満 */
    fun pauseMs(random: Double = Math.random()): Long = MIN_MS + (random.coerceIn(0.0, 0.999999) * JITTER_MS).toLong()

    fun pause() = Thread.sleep(pauseMs())
}
