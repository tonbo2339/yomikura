package com.tonbo2339.yomikura

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View

/**
 * 画面に戻ったあと、WebView の実際の表示を縮小して読み、「真っ黒・一色のまま」になっていないかを開発用ログ (分類 screen) に残す。
 * 真っ黒になる件 (描画が更新されない) が、いつ・どの設定で起きたかを、気づかなくても残すため。表示は端末の画面から読む (PixelCopy。画面が出ているときだけ)。
 * 数字の出し方は [RenderStats]。開発用ログが切れているときは何もしない。
 */
object RenderProbe {
    private const val W = 48
    private const val H = 96

    /** delayMs あとに、view の表示を 1 回読んで残す。reason = 何のあとの読み取りか (ログに添える) */
    fun check(activity: Activity, view: View, reason: String, delayMs: Long) {
        if (!DevLog.on(DevLog.Category.SCREEN)) return
        view.postDelayed({ sample(activity, view, reason) }, delayMs)
    }

    private fun sample(activity: Activity, view: View, reason: String) {
        if (activity.isFinishing || !view.isShown || view.width <= 0 || view.height <= 0) {
            DevLog.eventOf(DevLog.Category.SCREEN, "render", "after" to reason, "skipped" to "not shown")
            return
        }
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val src = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(activity.window, src, bmp, { result ->
                if (result == PixelCopy.SUCCESS) {
                    val px = IntArray(W * H)
                    bmp.getPixels(px, 0, W, 0, 0, W, H)
                    val s = RenderStats.analyze(px)
                    DevLog.eventOf(DevLog.Category.SCREEN, "render", "after" to reason, *s.fields().toTypedArray())
                    if (s.flat) saveShot(activity, view, reason) // 一色のまま (真っ黒の疑い): その瞬間の表示を残す
                } else {
                    DevLog.eventOf(DevLog.Category.SCREEN, "render", "after" to reason, "copyFailed" to result)
                }
                bmp.recycle()
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            DevLog.eventOf(DevLog.Category.SCREEN, "render", "after" to reason, "error" to e.javaClass.simpleName)
        }
    }

    /** 一色のまま残っていた表示を、実寸の 1/4 の PNG にして filesDir/blackshots/ へ (古い分は [ShotStore] が整理する) */
    private fun saveShot(activity: Activity, view: View, reason: String) {
        val w = (view.width / 4).coerceAtLeast(1)
        val h = (view.height / 4).coerceAtLeast(1)
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val src = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(activity.window, src, bmp, { result ->
                if (result == PixelCopy.SUCCESS) {
                    val name = ShotStore.save(activity.filesDir, bmp, System.currentTimeMillis())
                    DevLog.eventOf(DevLog.Category.SCREEN, "render shot saved", "file" to name, "after" to reason)
                }
                bmp.recycle()
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            bmp.recycle()
        }
    }
}
