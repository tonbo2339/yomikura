package com.tonbo2339.yomikura

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** 通知・ロック画面のボタンから読み上げへ送る操作の受け口 (ReadingAids が持ち主。アプリが無ければ null) */
object ReadAloudControl {
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val STOP = "stop"

    @Volatile var handler: ((String) -> Unit)? = null
}

/**
 * 読み上げの前景サービス。読み上げは画面 (ReadingAids / ReadAloud) が行い、これは (1) 画面を消す・ほかのアプリに移ってもアプリを止められないようにし、
 * (2) 通知とロック画面に「一時停止 / 再開」「止める」を出す。読み上げが終わる・止めると [hide] で消える。
 */
class ReadAloudService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val action = intent?.action) {
            ACTION_SHOW -> show(intent.getBooleanExtra(EXTRA_PAUSED, false), intent.getStringExtra(EXTRA_TITLE).orEmpty())
            ACTION_HIDE -> {
                // startForegroundService で始めたサービスは、止める前に startForeground を呼ばないとアプリが落ちる。一度出してから消す
                show(false, "")
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ReadAloudControl.PAUSE, ReadAloudControl.RESUME, ReadAloudControl.STOP -> {
                val h = ReadAloudControl.handler
                if (h == null) stopSelf() else h(action)
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun show(paused: Boolean, title: String) {
        val n = notification(this, paused, title)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(Notices.Id.READ_ALOUD, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(Notices.Id.READ_ALOUD, n)
        } catch (e: Exception) {
            Diag.w(TAG, "startForeground", e) // 始められなくても、読み上げは画面のまま続く
            stopSelf()
        }
    }

    companion object {
        private const val TAG = "Yomikura.readaloud"
        private const val ACTION_SHOW = "com.tonbo2339.yomikura.readaloud.SHOW"
        private const val ACTION_HIDE = "com.tonbo2339.yomikura.readaloud.HIDE"
        private const val EXTRA_PAUSED = "paused"
        private const val EXTRA_TITLE = "title"

        /** 通知を出してから、まだ消していないか (消す操作が、始める前に来ても落ちないように、消すのは出した後だけ) */
        @Volatile private var shown = false

        /** 通知を出す (読み始め・一時停止・再開のたびに呼ぶ。同じ通知を更新する) */
        fun show(ctx: Context, paused: Boolean, title: String) {
            shown = true
            try {
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, ReadAloudService::class.java).setAction(ACTION_SHOW).putExtra(EXTRA_PAUSED, paused).putExtra(EXTRA_TITLE, title),
                )
            } catch (e: Exception) {
                Diag.w(TAG, "show", e)
            }
        }

        /** 読み上げが終わった・止めた: サービスと通知を消す */
        fun hide(ctx: Context) {
            if (!shown) return
            shown = false
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, ReadAloudService::class.java).setAction(ACTION_HIDE))
            } catch (e: Exception) { Diag.w(TAG, "hide", e) }
        }

        private fun control(ctx: Context, action: String): PendingIntent =
            PendingIntent.getService(ctx, action.hashCode(), Intent(ctx, ReadAloudService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        private fun notification(ctx: Context, paused: Boolean, title: String): Notification {
            val b = Notices.builder(ctx, Notices.Channel.READ_ALOUD, branded = false)
                .setSmallIcon(R.drawable.ic_read_aloud)
                .setContentTitle(if (paused) "読み上げを一時停止しています" else "読み上げています")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // ロック画面にも出す
                .setContentIntent(Notices.openApp(ctx, Notices.Id.READ_ALOUD, Intent.ACTION_MAIN))
                .addAction(R.drawable.ic_read_aloud, if (paused) "再開" else "一時停止", control(ctx, if (paused) ReadAloudControl.RESUME else ReadAloudControl.PAUSE))
                .addAction(R.drawable.ic_close, "止める", control(ctx, ReadAloudControl.STOP))
            if (title.isNotBlank()) b.setContentText(title)
            return b.build()
        }
    }
}
