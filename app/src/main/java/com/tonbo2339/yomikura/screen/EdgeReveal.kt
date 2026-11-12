package com.tonbo2339.yomikura

import android.view.MotionEvent

/**
 * 隠したバーを、置いてある端から内側へ「しゅっ」とスワイプして出す動きを見つける。触りの知らせを見るだけで、ページへの操作は横取りしない。
 * 端 = 上のバーなら上の余白 (状態バー) のすぐ下、左右のバーなら画面の左右の端。左右の端は OS の「戻る」ジェスチャーと重なるので、
 * MainActivity が root の systemGestureExclusionRects で、端の中ほどを戻るジェスチャーから外している。
 */
class EdgeReveal(private val dp: Float, private val onReveal: () -> Unit) {
    private var startX = 0f
    private var startY = 0f
    private var armed = false

    /** pos = バーの位置 ("top" / "left" / "right")、width = ウィンドウの幅 (px)、top = 上の余白 (状態バーなど) の高さ (px)、active = いま隠していて、出してよいか */
    fun onTouch(ev: MotionEvent, pos: String, width: Int, top: Int, active: Boolean) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x; startY = ev.y // ウィンドウの座標 (rawX は画面の座標。分割画面・フリーフォームでウィンドウが画面の端にないとき、端を取り違える)
                armed = active && inZone(pos, startX, startY, width, top, ZONE_DP * dp)
            }
            MotionEvent.ACTION_MOVE -> if (armed && reached(pos, ev.x - startX, ev.y - startY, REACH_DP * dp)) { armed = false; onReveal() }
            // 指が増えたら (ピンチ・2 本指) スワイプではない
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> armed = false
        }
    }

    companion object {
        const val ZONE_DP = 64f  // 端からこの幅の中で始まったスワイプだけを見る (上のバーは、状態バーが触りを受ける所のすぐ下から)
        const val REACH_DP = 28f // 内側へこれだけ動いたら出す

        /** 触り始め (x, y) が、バーの端の側の範囲か。上のバーは、状態バーの中の触りは OS が受けるので、アプリに来るのはその下から。下限は付けない (端末で、状態バーの高さの測りがずれることがある) */
        fun inZone(pos: String, x: Float, y: Float, width: Int, top: Int, zone: Float) = when (pos) {
            "left" -> x <= zone
            "right" -> x >= width - zone
            else -> y <= top + zone
        }

        /** 触り始めからの動き (dx, dy) が、内側へ reach 以上で、横ぶれより内向きが大きいか */
        fun reached(pos: String, dx: Float, dy: Float, reach: Float): Boolean {
            val (inward, across) = when (pos) {
                "left" -> dx to dy
                "right" -> -dx to dy
                else -> dy to dx
            }
            return inward > reach && Math.abs(across) < inward
        }
    }
}
