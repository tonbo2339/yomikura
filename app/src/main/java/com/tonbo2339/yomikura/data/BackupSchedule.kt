package com.tonbo2339.yomikura

/**
 * 自動バックアップの時期の決まり。送り先に関わらず共通。Android に依存しない (単体テストで動かせる)。
 *
 * - 自動の間隔は 6 / 12 / 24 時間 (既定 12)。前回の成功から間隔以上たっていれば送る。失敗したら 1 時間はやり直さない
 * - 残す数は 1〜[KEEP_MAX] (既定 10)。0 は無制限 (以前の設定として残っていれば効くが、画面からは選ばない)
 */
object BackupSchedule {
    /** 自動の間隔として選べる時間 (設定画面の BACKUP_HOURS と同じ) */
    val HOURS = listOf(6, 12, 24)
    const val DEFAULT_HOURS = 12
    const val KEEP_MAX = 1000
    const val DEFAULT_KEEP = 10
    const val HOUR_MS = 60 * 60 * 1000L
    /** 失敗してから、次に自動でやり直すまで */
    const val RETRY_MS = HOUR_MS

    fun hoursOrDefault(n: Int): Int = if (n in HOURS) n else DEFAULT_HOURS

    fun keepOrDefault(n: Int, allowUnlimited: Boolean = true): Int =
        if ((allowUnlimited && n == 0) || n in 1..KEEP_MAX) n else DEFAULT_KEEP

    /** 次に自動で送れるようになる時刻。0 = すぐ (まだ一度も成功していない) */
    fun nextAt(lastOk: Long, hours: Int): Long = if (lastOk == 0L) 0 else lastOk + hours * HOUR_MS

    /**
     * 自動で送る時期か。auto = 自動がオン、busy = 送っている途中、ready = 送れる状態 (送り先の設定があり、つながっている)。
     * lastOk / lastTry = 前回の成功 / 前回の試み (失敗を含む) の時刻
     */
    fun isDue(auto: Boolean, busy: Boolean, ready: Boolean, now: Long, lastOk: Long, lastTry: Long, hours: Int): Boolean =
        auto && !busy && ready && now - lastOk >= hours * HOUR_MS && now - lastTry >= RETRY_MS

    /** 成功が途絶えてから、気づいてもらうための通知を出すまで */
    const val STALE_MS = 3 * 24 * HOUR_MS

    /**
     * 「送れていない」通知を出すか。自動がオンで、一度は成功していて、最後の成功から STALE_MS 以上たち、前回の通知からも STALE_MS 以上たっているとき。
     * (一度も成功していない間は出さない: 送り先を決めた直後や、まだ使い始めていないだけかもしれないため)
     */
    fun shouldAlertStale(auto: Boolean, now: Long, lastOk: Long, lastAlert: Long): Boolean =
        auto && lastOk > 0 && now - lastOk >= STALE_MS && now - lastAlert >= STALE_MS

    /**
     * 次に自動で送る時期まで待つ時間 (ミリ秒)。自動がオフなら null。間隔と、失敗からのやり直しの両方を満たす時刻まで。
     * Wi-Fi に着いたら動くジョブの最短の待ちに使う
     */
    fun delayUntilDue(auto: Boolean, now: Long, lastOk: Long, lastTry: Long, hours: Int): Long? {
        if (!auto) return null
        val due = maxOf(lastOk + hours * HOUR_MS, lastTry + RETRY_MS)
        return (due - now).coerceAtLeast(0)
    }
}
