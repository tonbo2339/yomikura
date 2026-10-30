package com.tonbo2339.yomikura

import android.content.Context
import org.json.JSONObject

/**
 * 手動の「更新を確認」を続けて押せない間 (サイトへ続けてアクセスしないため)。計算 (remainingMs / lastOf) は Android に依存しない (単体テストで動かせる)。
 * 前回の確認 ([lastOf]。起動時の自動・裏での確認も含む) から [MINUTES] 分は、手動で確認を始めない。
 * 止める本体は CollectController.start、リストのボタンの表示は NHStore.collectWaitMs (list.html) が、どちらもここを見る。
 */
object CollectCooldown {
    const val MINUTES = 10
    /** 確認を始めた時刻 (保存データ)。途中で止まった確認 (ページが進まない・時間切れ) は lastCollect を書かないので、始めた時刻でも数える */
    private const val KEY_STARTED = "collectStartAt"

    /** 前回の確認の時刻: 終わった時刻 (lastCollect。ui-collect.js が書く) と、始めた時刻の新しい方 */
    fun lastOf(data: JSONObject): Long = maxOf(data.optLong("lastCollect", 0L), data.optLong(KEY_STARTED, 0L))

    /** 起動時の自動確認の時期か: 前回の確認 ([lastOf]) から hours 時間より長くたっている。前回が無ければ (0) 時期。Android に依存しない (単体テスト用) */
    fun autoDue(last: Long, hours: Int, now: Long): Boolean = now - last > hours.coerceAtLeast(1) * 3_600_000L

    /** 確認を始めた (画面の確認・裏での確認の始まりで呼ぶ。確認画面で分かれた続きの巡回では呼ばない) */
    fun markStarted(ctx: Context) = NHData.update(ctx) { it.put(KEY_STARTED, System.currentTimeMillis()) }

    /** あと何ミリ秒待つか (0 = 押せる)。端末の時計が戻って lastCollect が未来になったときは待たない */
    fun remainingMs(lastCollect: Long, now: Long): Long {
        if (lastCollect <= 0L || lastCollect > now) return 0L
        return (lastCollect + MINUTES * 60_000L - now).coerceAtLeast(0L)
    }

    /** 画面に出す「あと N 分」の N (切り上げ。0 なら押せる) */
    fun remainingMinutes(ms: Long): Int = ((ms + 59_999L) / 60_000L).toInt()
}
