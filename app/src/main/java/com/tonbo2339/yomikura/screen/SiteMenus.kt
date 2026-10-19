package com.tonbo2339.yomikura

import android.view.View
import com.tonbo2339.yomikura.SiteConfig.HAMELN_MENU
import com.tonbo2339.yomikura.SiteConfig.KAKUYOMU_MENU
import com.tonbo2339.yomikura.SiteConfig.NAROU_MENU
import com.tonbo2339.yomikura.SiteConfig.NAROU_MYPAGE
import com.tonbo2339.yomikura.SiteConfig.R18_BOOKMARK_PLAIN
import com.tonbo2339.yomikura.SiteConfig.RANKING_SLOT

/**
 * サイトのタブを押したときのメニュー (トップページ / ランキング / 作品検索 / しおり・ブックマーク / マイページ) の項目と表示。
 * 項目の元は SiteConfig。ここは、設定のランキングの行き先への差し替えと、R18 のまとめ方、表示だけを持つ。
 */
class SiteMenus(private val theme: BarTheme, private val cfg: AppSettings, private val navigate: (String) -> Unit) {

    /** サイトのメニュー: 「ランキング」は設定の行き先へ差し替える。項目はどのサイトも同じ。 */
    fun forSite(key: String): List<Pair<String, String>> {
        val base = when (key) {
            "hameln" -> HAMELN_MENU
            "kakuyomu" -> KAKUYOMU_MENU
            else -> NAROU_MENU
        }
        return base.map { (label, url) -> label to (if (url == RANKING_SLOT) cfg.siteRanking(key) else url) }
    }

    /**
     * R18 のメニュー。サイト名のタブ (1 サイト) と、「なろう」の素早い 2 回タップ (表示中の R18 全部) で同じ項目を出す。
     * 複数サイトのときは「サイト名 + 項目名」で、項目ごとにまとめて並べる。しおり・ブックマークとマイページはなろうと共通。
     */
    fun r18(sites: List<SiteConfig.Site>): List<Pair<String, String>> {
        val multi = sites.size > 1
        fun named(s: SiteConfig.Site, item: String) = if (multi) "${s.label} $item" else item
        return sites.map { named(it, "トップページ") to it.top } +
            sites.map { named(it, "ランキング") to cfg.siteRanking(it.key) } +
            sites.map { named(it, "作品検索") to r18Search(it.key) } +
            // しおり・マイページは、R18 の 3 サイトで 1 つずつ (サイト名を付けて、なろうのマイページと区別する)
            listOf((if (multi) "R18 ブックマーク" else "ブックマーク") to R18_BOOKMARK_PLAIN,
                (if (multi) "R18 マイページ" else "マイページ") to NAROU_MYPAGE)
    }

    /** ノクターンなどの作品検索 (トップは /top/top/ なので、ホストから作る)。 */
    private fun r18Search(key: String) = "https://$key.syosetu.com/search/search/"

    /** メニューを出す: 記号付きの項目を並べ、選んだ URL を開く。 */
    fun show(anchor: View, entries: List<Pair<String, String>>) {
        val items = entries.map { (label, url) -> MainMenu.Action(iconFor(label), label) { navigate(url) } }
        // 3 サイト分まとめた R18 の一覧は、名前が長いので広めに
        theme.menu().show(anchor, top = emptyList(), groups = listOf(items), alignEnd = false, widthDp = if (entries.size > 5) 320 else 260)
    }

    /** 項目名から記号を選ぶ (「ノクターン ランキング」のように前にサイト名が付いていても、後ろの言葉で決める)。 */
    private fun iconFor(label: String): Int = when {
        label.endsWith("トップページ") -> R.drawable.ic_home
        label.endsWith("ランキング") -> R.drawable.ic_trophy
        label.endsWith("作品検索") -> R.drawable.ic_search
        label.endsWith("マイページ") -> R.drawable.ic_person
        else -> R.drawable.ic_book // しおり・ブックマーク・フォロー中の小説
    }

    /** 1 回タップで通常のメニュー、素早く 2 回タップで別のメニュー (2 回目を待つぶん少し遅れて開く)。2 回目のメニューが無ければ待たない。 */
    inner class DoubleTap(
        private val hasSecond: Boolean,
        private val second: () -> List<Pair<String, String>>,
        private val first: () -> List<Pair<String, String>>,
    ) : View.OnClickListener {
        private var pending: Runnable? = null

        override fun onClick(v: View) {
            pending?.let {
                v.removeCallbacks(it)
                pending = null
                show(v, second())
                return
            }
            if (!hasSecond) return show(v, first())
            val open = Runnable { pending = null; show(v, first()) }
            pending = open
            v.postDelayed(open, DOUBLE_TAP_MS)
        }
    }

    private companion object { const val DOUBLE_TAP_MS = 320L }
}
