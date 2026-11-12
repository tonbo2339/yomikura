package com.tonbo2339.yomikura

/**
 * バーを隠している間の目印 (細い棒・小さなボタン) の、縦の位置と大きさの決め方。
 * 目印を描く BarHandle、左右のバーを目印と同じ高さに置く MainActivity.placeRail、OS の「戻る」ジェスチャーから外す範囲が、同じ計算を使う
 * (棒を動かしたのに、外す範囲が真ん中のままだと、棒の所でスワイプしても「戻る」になってしまう)。Android に依存しない (単体テストで動かせる)。
 *
 * 縦の位置は settings.barButtonPos (0〜1。0 = 上、1 = 下、既定 0.5)。小さなボタンをなぞる・設定の「縦の位置」で変わる。棒と小さなボタンで共通。
 */
object HandleLayout {
    const val BUTTON_DP = 44
    const val LINE_LENGTH_DP = 64

    /** 目印の縦の長さ (dp)。小さなボタンは 44、棒は 64 */
    fun lengthDp(button: Boolean): Int = if (button) BUTTON_DP else LINE_LENGTH_DP

    /** 目印の上端 (px。ページの箱の上端から)。frac = 0〜1 (箱の高さのうち、目印が動ける範囲での割合) */
    fun top(frac: Double, contentHeight: Int, length: Int): Int =
        ((contentHeight - length).coerceAtLeast(0) * frac.coerceIn(0.0, 1.0)).toInt()

    /** 戻るジェスチャーから外す範囲 (画面の座標。端の幅 32dp × 高さ 200dp。OS が認める上限) */
    class Zone(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * pos = バーの位置、width / height = バーのある画面 (root) の大きさ、centerY = 目印の中心の高さ (root の座標)。
     * 目印を中心にして、画面の中に収める。上のバーのときは無し。
     */
    fun exclusion(pos: String, width: Int, height: Int, centerY: Int, dp: Float): Zone? {
        val h = (200 * dp).toInt()
        val w = (32 * dp).toInt()
        val top = (centerY - h / 2).coerceIn(0, maxOf(0, height - h))
        return when (pos) {
            "left" -> Zone(0, top, w, top + h)
            "right" -> Zone(width - w, top, width, top + h)
            else -> null
        }
    }
}
