package com.tonbo2339.yomikura

/**
 * 画面の表示を縮小して読んだ点 (ARGB の Int の並び) から、「真っ黒・真っ白のまま」かを見分ける数字を出す。Android に依存しない (単体テスト用)。
 * 普通のページは文字や図があって明るさにばらつきが出る。描画が更新されず、一色のまま残っているときは、ばらつきがほとんど無い。
 */
object RenderStats {
    /** avgLuma = 明るさの平均 (0 黒〜255 白)、stddev = 明るさのばらつき、blackPct = ほぼ黒の点の割合 (%)、colors = 色の種類 (粗く分けた数) */
    data class Stats(val avgLuma: Int, val stddev: Int, val blackPct: Int, val colors: Int) {
        /** ほぼ一色 (ばらつきが小さい) */
        val flat: Boolean get() = stddev < FLAT_STDDEV

        fun fields(): List<Pair<String, Any?>> = listOf("avgLuma" to avgLuma, "stddev" to stddev, "blackPct" to blackPct, "colors" to colors, "flat" to flat)
    }

    private const val FLAT_STDDEV = 3
    private const val BLACK_LUMA = 8

    fun analyze(pixels: IntArray): Stats {
        if (pixels.isEmpty()) return Stats(0, 0, 100, 0)
        var sum = 0L
        var black = 0
        val lumas = IntArray(pixels.size)
        val seen = HashSet<Int>()
        for ((i, p) in pixels.withIndex()) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = (299 * r + 587 * g + 114 * b) / 1000
            lumas[i] = luma
            sum += luma
            if (luma < BLACK_LUMA) black++
            seen.add(((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4))
        }
        val mean = sum.toDouble() / pixels.size
        val variance = lumas.sumOf { (it - mean) * (it - mean) } / pixels.size
        return Stats(mean.toInt(), Math.sqrt(variance).toInt(), black * 100 / pixels.size, seen.size)
    }
}
