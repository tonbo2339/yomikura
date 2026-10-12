package com.tonbo2339.yomikura

/**
 * 版 (flavor) ごとに中身が違う機能の窓口。共通のコードはここだけを見て、版の違いを知らない。
 * 実体は版ごとの `Edition` (その版のフォルダ src/<版> に、同じ名前で置く)。
 */

/** 通信先のホストを止めるか。公開版は何も止めない */
fun interface HostFilter {
    fun shouldBlock(host: String?): Boolean
}

/** 版によってだけある連携。公開版は何もしない */
interface ExtraHooks {
    /** 画面を開いたとき、時期なら連携先へ送る */
    fun autoIfDue()
}
