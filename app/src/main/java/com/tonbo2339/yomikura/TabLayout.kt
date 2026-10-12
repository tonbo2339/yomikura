package com.tonbo2339.yomikura

/**
 * 上部バーのタブの文字の割り付け。文字の幅の測り方 (measure) を渡してもらう形にして、Android に依存しないようにした (単体テスト用)。
 */
object TabLayout {
    /** 2 行目の頭に来てはいけない文字 (「ー」や小さい文字、句読点) */
    private const val NO_LINE_HEAD = "ーッャュョァィゥェォヮっゃゅょぁぃぅぇぉゎ、。・）」』"

    /** 名前を 2 行に分ける。真ん中で分け、2 行目の頭に NO_LINE_HEAD の文字が来るときは前へずらす。 */
    fun split(s: String): List<String> {
        if (s.length < 2) return listOf(s, "")
        var h = (s.length + 1) / 2
        while (h > 1 && s[h] in NO_LINE_HEAD) h--
        return listOf(s.take(h), s.drop(h))
    }

    /**
     * 1 段に固定して幅をそろえたときの、全タブ共通の文字の大きさ (sp) と、各タブに出す文字 (2 行にするものは改行入り)。
     * いちばん長い名前が 1 行に入る大きさ (9sp 以上) があればそれ、無ければ長い名前を 2 行に分けて入る大きさにする。
     * room = 文字に使える幅 (px)、measure(文字, sp) = 文字の幅 (px)。
     */
    fun oneRowText(labels: List<String>, room: Float, measure: (String, Float) -> Float): Pair<Float, List<String>> {
        val fit = { parts: List<String> -> 13f * room / (parts.maxOfOrNull { measure(it, 13f) } ?: 1f) }
        val one = fit(labels)
        if (one >= 9f) return minOf(13f, one) to labels
        // 2 行 (行の高さもタブに収まるよう 12sp まで)
        val split = labels.map(::split)
        val sp = fit(split.flatten()).coerceIn(8f, 12f)
        return sp to labels.mapIndexed { i, s -> if (measure(s, sp) <= room) s else split[i].joinToString("\n") }
    }
}
