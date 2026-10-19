package com.tonbo2339.yomikura

/**
 * 読み上げる文を、音声合成に渡せる長さに分ける。Android に依存しない (単体テストで動かせる)。
 * 音声合成には 1 回に渡せる長さの上限 (TextToSpeech.getMaxSpeechInputLength。端末によって 4000 など) があり、
 * 長い段落は文の切れ目 (。！？) で分ける。それでも長い文は、上限の長さで切る。どの塊がどの段落かを覚えておき、読んでいる段落に色をつけるのに使う。
 */
object TtsChunker {
    /** para = 段落の番号 (JS が渡した順)、text = 読む文 */
    data class Chunk(val para: Int, val text: String)

    /** 1 つの塊の長さの目安 (文字数)。短めにして、読んでいる段落の色を、こまめに進める */
    const val DEFAULT_MAX = 500

    private val SENTENCE_END = Regex("(?<=[。！？!?])")

    fun chunk(paragraphs: List<String>, max: Int = DEFAULT_MAX): List<Chunk> {
        require(max > 0)
        val out = ArrayList<Chunk>()
        paragraphs.forEachIndexed { i, raw ->
            val p = raw.trim()
            if (p.isEmpty()) return@forEachIndexed
            if (p.length <= max) { out += Chunk(i, p); return@forEachIndexed }
            // 文の切れ目で分け、max を超えない範囲でつなぐ
            val buf = StringBuilder()
            fun flush() { if (buf.isNotEmpty()) { out += Chunk(i, buf.toString()); buf.setLength(0) } }
            for (s in p.split(SENTENCE_END).filter { it.isNotEmpty() }) {
                if (s.length > max) { // 切れ目の無い長い文は、max で切る
                    flush()
                    s.chunked(max).forEach { out += Chunk(i, it) }
                } else {
                    if (buf.length + s.length > max) flush()
                    buf.append(s)
                }
            }
            flush()
        }
        return out
    }
}
