package com.tonbo2339.yomikura

/**
 * 手動の「更新を確認」を続けて押せない間 (サイトへ続けてアクセスしないため)。Android に依存しない (単体テストで動かせる)。
 * 前回の確認 (保存データの lastCollect = 確認が終わった時刻。起動時の自動・裏での確認も含む) から [MINUTES] 分は、手動で確認を始めない。
 * 止める本体は MainActivity.collectQuiet、リストのボタンの表示は NHStore.collectWaitMs (list.html) が、どちらもここを見る。
 */
object CollectCooldown {
    const val MINUTES = 10

    /** あと何ミリ秒待つか (0 = 押せる)。端末の時計が戻って lastCollect が未来になったときは待たない */
    fun remainingMs(lastCollect: Long, now: Long): Long {
        if (lastCollect <= 0L || lastCollect > now) return 0L
        return (lastCollect + MINUTES * 60_000L - now).coerceAtLeast(0L)
    }

    /** 画面に出す「あと N 分」の N (切り上げ。0 なら押せる) */
    fun remainingMinutes(ms: Long): Int = ((ms + 59_999L) / 60_000L).toInt()
}
