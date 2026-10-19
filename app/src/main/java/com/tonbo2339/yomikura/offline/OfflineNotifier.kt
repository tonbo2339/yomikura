package com.tonbo2339.yomikura

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * オフライン保存の通知: 保存中の 1 本 (進み・Wi-Fi 待ち・一時停止・残りの数。「一時停止 / 再開」「中止」のボタンつき) と、終わったときのお知らせ。
 * 何を出すかの材料は [Progress] で受け取り、通知の組み立てだけを持つ (保存の進行そのものは OfflineService)。
 */
class OfflineNotifier(private val ctx: Context) {
    private var doneCount = 0 // 終わりのお知らせの番号 (同時に複数出ても消えないように)

    /** 通知に出す、いまの保存の様子。job = いま保存している作品 (無ければ null)。 */
    class Progress(
        val job: SaveQueue.Job?,
        val ok: Int,
        val total: Int,
        val title: String,
        val paused: Boolean,
        val waitingWifi: Boolean,
        /** 順番待ちの作品の数 */
        val waiting: Int,
        /** Wi-Fi 待ちの作品の数 */
        val wifiWaiting: Int,
        /** 回線につながっていない (作品を始めずに待っている) */
        val offline: Boolean = false,
    )

    /** 保存中の通知を更新する (前景サービスで出している通知を差し替える)。 */
    fun refresh(p: Progress) { Notices.post(ctx, Notices.Id.OFFLINE_PROGRESS, progress(p)) }

    /** 保存中の 1 本の通知。 */
    fun progress(p: Progress): Notification {
        val cur = p.job
        val text = when {
            cur == null && p.waiting == 0 && p.wifiWaiting > 0 -> "Wi-Fi を待っています ・ ${p.wifiWaiting} 作品"
            cur == null && p.waiting > 0 && p.paused -> "一時停止中 ・ ${p.waiting} 作品"
            cur == null && p.waiting > 0 && p.offline -> "回線を待っています ・ ${p.waiting} 作品"
            cur == null -> "保存キューは空です"
            p.paused -> "一時停止中 ・ ${p.ok} / ${p.total} 話"
            p.waitingWifi -> "Wi-Fi を待っています ・ ${p.ok} / ${p.total} 話"
            else -> "${p.ok} / ${p.total} 話" + if (p.title.isNotEmpty() && p.title != cur.title) " ・ ${p.title}" else ""
        }
        val b = Notices.builder(ctx, Notices.Channel.OFFLINE_PROGRESS, branded = false)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(if (cur != null) "保存中: ${cur.title}" else "オフライン保存")
            .setContentText(text)
            .setSubText(if (p.waiting > 0) "あと ${p.waiting} 作品" else null)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(p.total.coerceAtLeast(1), p.ok, cur == null)
        b.addAction(0, if (p.paused) "再開" else "一時停止", if (p.paused) serviceAction(OfflineService.ACTION_RESUME, REQ_RESUME) else serviceAction(OfflineService.ACTION_PAUSE, REQ_PAUSE))
        b.addAction(0, "中止", serviceAction(OfflineService.ACTION_CANCEL, REQ_CANCEL))
        return b.build()
    }

    /** 終わった・止まったときのお知らせ (タップで保存キューの画面)。 */
    fun done(head: String, text: String, sub: String?) {
        val n = Notices.builder(ctx, Notices.Channel.OFFLINE_DONE)
            .setContentTitle(head)
            .setContentText(text)
            .setSubText(sub)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        Notices.post(ctx, Notices.Id.OFFLINE_DONE_BASE + (++doneCount % 50), n)
    }

    private fun openApp(): PendingIntent = Notices.openApp(ctx, REQ_OPEN, OfflineService.ACTION_OPEN_QUEUE)

    private fun serviceAction(action: String, req: Int): PendingIntent =
        PendingIntent.getService(
            ctx, req, Intent(ctx, OfflineService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val REQ_OPEN = 0
        private const val REQ_PAUSE = 1
        private const val REQ_RESUME = 2
        private const val REQ_CANCEL = 3
    }
}
