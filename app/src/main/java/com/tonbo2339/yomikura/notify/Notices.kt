package com.tonbo2339.yomikura

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 通知を出す処理の共通の入口。チャンネル・通知の番号・アイコン・タップで開く先・許可の確認・出すときの失敗の扱いは、ここだけに置く。
 * 各機能 (更新の確認・オフライン保存など) は、タイトルや本文などの中身だけを渡す。
 *
 * お知らせの通知 (branded = true): 小アイコンは積んだ本の形 (状態バーでは単色にされるので、選んだ色は出せない)、
 * 大アイコンは選んでいるランチャーアイコンそのもの (色つき)。
 * 進みの通知 (branded = false): 状態が分かるよう、呼び出し側が小アイコンを付ける。
 */
object Notices {
    /** チャンネル (id はユーザーの端末に残るので変えない)。 */
    enum class Channel(val id: String, val label: String, val importance: Int) {
        UPDATES("updates", "更新のお知らせ", NotificationManager.IMPORTANCE_DEFAULT),
        OFFLINE_PROGRESS("offline_progress", "オフライン保存の進み", NotificationManager.IMPORTANCE_LOW),
        OFFLINE_DONE("offline_done", "オフライン保存のお知らせ", NotificationManager.IMPORTANCE_DEFAULT),
    }

    /** 通知の番号 (同じ番号は上書きされる)。 */
    object Id {
        const val UPDATE = 1
        const val CHALLENGE = 2
        const val OFFLINE_PROGRESS = 4101
        const val OFFLINE_DONE_BASE = 4200
    }

    private const val LARGE_PX = 128
    private const val TAG = "Yomikura.notify"

    /** 通知を出してよいか (Android 13 以降は許可が要る)。 */
    fun allowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** チャンネルを (無ければ) 作り、そのチャンネルの組み立て役を返す。 */
    fun builder(ctx: Context, ch: Channel, branded: Boolean = true): NotificationCompat.Builder {
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch.id, ch.label, ch.importance))
        val b = NotificationCompat.Builder(ctx, ch.id)
        if (branded) {
            b.setSmallIcon(R.drawable.ic_stat_books)
            largeIcon(ctx)?.let { b.setLargeIcon(it) }
        }
        return b
    }

    /** 通知を出す。許可が取り消されていても例外は出さず、出せたかを返す。 */
    fun post(ctx: Context, id: Int, n: Notification): Boolean = try {
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        true
    } catch (e: SecurityException) {
        Diag.w(TAG, "notify $id", e)
        false
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
    }

    /**
     * タップでアプリを開く PendingIntent。action はアプリ側の分岐用、data は開くページ。
     * req は通知ごとに分ける (同じ中身の Intent は 1 つにまとめられるため)。
     */
    fun openApp(ctx: Context, req: Int, action: String, data: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (data != null) i.data = Uri.parse(data)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** いま選んでいるランチャーアイコンの絵。取れなければ null (小アイコンだけで出す)。 */
    private fun largeIcon(ctx: Context): Bitmap? = try {
        val d = LauncherIcon(ctx).currentDrawable()
        val bmp = Bitmap.createBitmap(LARGE_PX, LARGE_PX, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, LARGE_PX, LARGE_PX)
        d.draw(Canvas(bmp))
        bmp
    } catch (e: Exception) {
        Diag.w(TAG, "大アイコン", e)
        null
    }
}
