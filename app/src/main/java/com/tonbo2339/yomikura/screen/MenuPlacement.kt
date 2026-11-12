package com.tonbo2339.yomikura

/**
 * 左右のバー (縦のバー) のタブから開くメニューを出す位置。バーの上に重ねると、ほかのボタンや、押したタブ自身が隠れて、
 * 開いたまま別のタブを押せない・同じタブを押して閉じられない (メニューの項目を押してしまう) ので、バーの横に出す。
 * Android に依存しない (単体テストで動かせる)。
 */
object MenuPlacement {
    /**
     * メニューの左上 (x, y) を返す。anchor* = 押したタブの位置 (ウィンドウの座標) と大きさ、menu* = メニューの大きさ、win* = ウィンドウの大きさ、margin = 画面の端の余白 (px)。
     * 左のバーなら右へ、右のバーなら左へ出す。上下は、タブの上端から下に収まればそこ、収まらなければタブの下端にそろえて上へ。いずれも画面の中に収める。
     */
    fun beside(
        anchorLeft: Int, anchorTop: Int, anchorW: Int, anchorH: Int,
        menuW: Int, menuH: Int, winW: Int, winH: Int, margin: Int,
    ): Pair<Int, Int> {
        val toRight = anchorLeft + anchorW / 2 < winW / 2
        val x = (if (toRight) anchorLeft + anchorW else anchorLeft - menuW).coerceIn(margin, maxOf(margin, winW - menuW - margin))
        val fitsBelow = winH - anchorTop >= menuH + margin
        val y = (if (fitsBelow) anchorTop else anchorTop + anchorH - menuH).coerceIn(margin, maxOf(margin, winH - menuH - margin))
        return x to y
    }
}
