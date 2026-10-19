package com.tonbo2339.yomikura

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.tonbo2339.yomikura.SiteConfig.LIST_URL
import com.tonbo2339.yomikura.SiteConfig.SETTINGS_URL
import com.tonbo2339.yomikura.SiteConfig.SITES

/**
 * 画面の上のバー: 1 段目 (再読み込み / サイトのタブ / リスト / メニュー)・タブの 2 段目・保存の帯・境目の線をまとめた箱 (header)。
 * スクロールで箱ごと隠す (HeaderScroll)。タブの見た目は SiteTabs、押したときのメニューは SiteMenus、保存の帯は SaveStrip。
 *
 * 設定で非表示にしたサイトは出さない。並びは settings.tabOrder。読書ページではサイトのヘッダーを消すので、主な行き先はここのメニューから行く
 * (ほかのページはサイトのヘッダーも出る)。
 * タブは名前の文字の幅を測って、1 段 (更新 / タブ / リスト / 設定) に収まるか決める:
 *   そのまま収まる → 1 段。▾ と余白を削れば収まる → 1 段 (compact)。収まらない → タブだけ 2 段目 (横スクロールにはしない)。
 *   設定の「タブを 1 段にまとめる」(settings.tabsOneRow) がオンなら、いつも 1 段で、タブの幅をそろえる
 *   (いちばん長い名前が入らなければ ▾ と余白を省き、それでも入らなければ文字を縮める)。
 * タブの点の色は settings.siteColors、背景を塗るかは settings.tabsTinted。メニューは開くたびに作る (今の設定とページで変わる)。
 */
class TopBar(
    private val ctx: Context,
    private val theme: BarTheme,
    private val cfg: AppSettings,
    saveQueue: SaveQueue,
    private val menus: SiteMenus,
    private val navigate: (String) -> Unit,
    private val reload: () -> Unit,
    private val openMainMenu: (View) -> Unit,
    /** アプリ内ページ (リスト・設定など) が出ているか / 設定の画面が出ているか (UI スレッドから) */
    private val onAppPage: () -> Boolean,
    private val onSettingsPage: () -> Boolean,
) {
    private val dp = theme.dp
    private val tabs = SiteTabs(ctx, theme)

    /** 1 段目 */
    private val bar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
    }
    /** タブが 1 段に収まらないときの 2 段目 (横スクロールにせず、段を分ける) */
    private val tabRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((2 * dp).toInt(), 0, (2 * dp).toInt(), (4 * dp).toInt())
        visibility = View.GONE
    }
    /** バーとページの境目の細い線 */
    private val divider = View(ctx)
    val saveStrip = SaveStrip(ctx, theme, saveQueue) { navigate(SiteConfig.QUEUE_URL) }
    /** 上部バー + タブの 2 段目 + 保存の帯 + 区切り線をまとめた箱 (スクロールで隠す) */
    val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, (52 * dp).toInt()))
        addView(tabRow, LinearLayout.LayoutParams(MATCH_PARENT, (40 * dp).toInt()))
        addView(saveStrip.view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(divider, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, (0.5f * dp).toInt())))
    }
    val scroll = HeaderScroll(header, dp)

    /** 上部バー左端の「再読み込み」。アプリ内の画面 (リスト・設定など) では見えなくする */
    private var reloadButton: View? = null
    /** 上部バー右端の「⋮」。リストなどアプリ内の画面 (設定を除く) では歯車になり、押すと設定を開く */
    private var menuButton: View? = null
    private var badge: TextView? = null

    /** バーの中身を作り直す (表示するサイト・タブの並び・テーマ・画面の幅が変わったとき)。 */
    fun fill() {
        bar.removeAllViews()
        tabRow.removeAllViews()
        val specs = tabSpecs()
        // 1 段目でタブに使える幅 = 画面の幅 - バーの左右の余白 - アイコン 3 つ
        val avail = ctx.resources.configuration.screenWidthDp * dp - 8 * dp - 3 * 44 * dp
        val oneRow = cfg.settings().optBoolean("tabsOneRow")
        // 幅をそろえるなら「いちばん長い名前 × タブの数」、そろえないなら「名前ごとの幅の合計」が入るか
        val total = { compact: Boolean ->
            if (oneRow) (specs.maxOfOrNull { tabs.need(it.label, compact) } ?: 0f) * specs.size
            else specs.sumOf { tabs.need(it.label, compact).toDouble() }.toFloat()
        }
        val compact = total(false) > avail
        val twoRows = compact && !oneRow && total(true) > avail
        val tinted = cfg.settings().optBoolean("tabsTinted")
        val tabParent = if (twoRows) tabRow else bar
        // 左端は「再読み込み」(ブラウザと同じ)。更新の確認は、リスト画面の更新アイコンから。設定 showReload (既定オン)。オフでもメニュー (⋮) から再読み込みできる
        reloadButton = if (cfg.showReload()) iconButton(R.drawable.ic_refresh, "再読み込み") { reload() }.also { bar.addView(it) } else null
        updateButtons()
        if (twoRows) bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f)) // タブは 2 段目。1 段目は左右のアイコンだけ
        // 幅: 1 段に固定ならそろえる。そうでなければ名前の文字の幅に比例させる (余った幅も、足りない分も、長い名前ほど多く受け持つ)
        val (fixedSp, texts) = if (oneRow && compact) tabs.oneRowText(specs.map { it.label }, avail / specs.size) else 0f to specs.map { it.label }
        specs.forEachIndexed { i, sp ->
            val weight = if (oneRow) 1f else tabs.need(sp.label, compact)
            tabParent.addView(tabs.tab(texts[i], sp.color, weight, compact, tinted, fixedSp).also(sp.setup))
        }
        bar.addView(iconButton(R.drawable.ic_book, "リスト", badge = true) { navigate(LIST_URL) })
        menuButton = iconButton(R.drawable.ic_more, "メニュー") { if (useGear()) navigate(SETTINGS_URL) else openMainMenu(it) }.also { bar.addView(it) }
        updateButtons()
        tabRow.visibility = if (twoRows) View.VISIBLE else View.GONE
        if (scroll.hidden) scroll.showNow() // 段数が変わるかもしれないので、作り直したときは出して測り直す
        updateListCount()
    }

    /** 表示するタブ。R18 は、既定ではバーに出さず「なろう」を素早く 2 回タップしたときだけメニューが出る。設定で「サイト名のタブ」にするか、なろうを隠しているときは、ノクターンなどを個別のタブで出す。 */
    private fun tabSpecs(): List<SiteTabs.Spec> {
        val specs = mutableListOf<SiteTabs.Spec>()
        val r18Sites = SITES.filter { it.r18 && cfg.siteOn(it.key) }
        val r18Tabs = r18Sites.isNotEmpty() && (!cfg.siteOn("narou") || cfg.settings().optString("r18Mode") == "tabs")
        fun spec(site: SiteConfig.Site, setup: (LinearLayout) -> Unit) =
            SiteTabs.Spec(site.key, cfg.siteName(site.key, site.label), cfg.siteColor(site.key) ?: BarTheme.siteColor(site.key), setup)
        fun site(key: String) = SITES.first { it.key == key }
        if (cfg.siteOn("narou")) specs += spec(site("narou")) { t ->
            if (!r18Tabs && r18Sites.isNotEmpty()) t.setOnClickListener(menus.DoubleTap(true, { menus.r18(r18Sites) }, { menus.forSite("narou") }))
            else t.setOnClickListener { v -> menus.show(v, menus.forSite("narou")) }
        }
        for (key in listOf("hameln", "kakuyomu")) {
            if (cfg.siteOn(key)) specs += spec(site(key)) { t -> t.setOnClickListener { v -> menus.show(v, menus.forSite(key)) } }
        }
        if (r18Tabs) r18Sites.forEach { s -> specs += spec(s) { t -> t.setOnClickListener { v -> menus.show(v, menus.r18(listOf(s))) } } }
        // 並び (settings.tabOrder)。書いていないサイトは既定の順のまま後ろへ (sortBy は安定)
        val order = cfg.settings().optJSONArray("tabOrder")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        specs.sortBy { sp -> order.indexOf(sp.key).let { if (it < 0) Int.MAX_VALUE else it } }
        return specs
    }

    /** 右端などに置くアイコンボタン。badge = true ならリストの件数バッジを重ねる。 */
    private fun iconButton(icon: Int, desc: String, badge: Boolean = false, onClick: (View) -> Unit): View {
        val size = (44 * dp).toInt()
        val frame = FrameLayout(ctx).apply {
            contentDescription = desc
            background = theme.ripple(theme.pill(Color.WHITE))
            isClickable = true
            setOnClickListener { onClick(it) }
            layoutParams = LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_VERTICAL }
        }
        frame.addView(ImageView(ctx).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(theme.pal.text)
        }, FrameLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt(), Gravity.CENTER))
        if (badge) {
            this.badge = TextView(ctx).apply {
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                minWidth = (17 * dp).toInt()
                setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
                background = theme.pill(BarTheme.ACCENT).apply { setStroke((2 * dp).toInt(), theme.pal.bg) }
            }
            frame.addView(this.badge, FrameLayout.LayoutParams(WRAP_CONTENT, (17 * dp).toInt(), Gravity.TOP or Gravity.END).apply {
                topMargin = (4 * dp).toInt(); marginEnd = (2 * dp).toInt()
            })
        }
        return frame
    }

    /** 歯車にするか: リストなどアプリ内の画面で、設定の画面そのものではないとき (メニューに出す項目が少ないので、設定へ直接行ける)。 */
    private fun useGear() = onAppPage() && !onSettingsPage()

    /**
     * 左右のボタンを、今の画面に合わせる。
     * 再読み込みはサイトのページだけ (アプリ内の画面では、場所は残したまま見えなくする)。
     * 右端は、サイトのページは ⋮、リストなどアプリ内の画面は歯車、設定の画面は何も出さない (場所は残す)。
     */
    fun updateButtons() {
        reloadButton?.visibility = if (onAppPage()) View.INVISIBLE else View.VISIBLE
        val gear = useGear()
        menuButton?.visibility = if (onSettingsPage()) View.INVISIBLE else View.VISIBLE
        (menuButton as? FrameLayout)?.let { f ->
            f.contentDescription = if (gear) "設定" else "メニュー"
            (f.getChildAt(0) as? ImageView)?.setImageResource(if (gear) R.drawable.ic_settings else R.drawable.ic_more)
        }
    }

    /** リストのバッジ (読了以外の件数) を保存データから更新する。 */
    fun updateListCount() {
        val arr = cfg.data().optJSONArray("readLater")
        val nsfw = cfg.nsfwOn()
        // NSFW モード中は R18 の作品を数えない (リストにも出さないため)
        val n = if (arr == null) 0 else (0 until arr.length()).count {
            val x = arr.optJSONObject(it)
            x?.optBoolean("done") == false && !(nsfw && (x.optString("site") == "narou18" || x.optBoolean("r18")))
        }
        badge?.text = n.toString()
        badge?.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    /** バーの地色・区切り線・ステータスバーの文字色を今のテーマに合わせる。root = 画面全体 (バーの地色が見える所)。 */
    fun applyColors(root: View, window: Window, onQueuePage: Boolean) {
        root.setBackgroundColor(theme.pal.bg)
        divider.setBackgroundColor(theme.pal.divider)
        saveStrip.refresh(onQueuePage)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !theme.isNight
            isAppearanceLightNavigationBars = !theme.isNight
        }
    }
}
