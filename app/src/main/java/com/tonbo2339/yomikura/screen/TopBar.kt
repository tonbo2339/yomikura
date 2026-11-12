package com.tonbo2339.yomikura

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
    /** 新しい版が見つかっていて、まだ知らせていないか (⋮ / 歯車に点を出す) */
    private val updateDot: () -> Boolean,
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
    /** バーの上の空き (全画面で、カメラの切り欠きや時計の下へバーを下げるため。ふだんは高さ 0) */
    private val topSpacer = View(ctx)

    /** カメラの切り欠きと時計の下へ、バー (上のバーと、左右のバーの上) を下げる。px = 下げる量 */
    fun setTopInset(px: Int) {
        topInset = px
        applyTopInset()
    }

    private var topInset = 0

    /** いま、バーを下げている量 (px。全画面で切り欠きを無視するときのカメラ・時計の分)。浮かぶカードも、これより上へは置かない */
    val topInsetPx get() = topInset

    /** 下げる量を、いまのバーの置き場所に合わせて入れる。上のバーは上の空き (topSpacer)、左右のバーは縦のバーの上の余白。左右のときに上の空きを残すと、ページの上に空白の帯が出る */
    private fun applyTopInset() {
        val top = onTop
        val lp = topSpacer.layoutParams as? LinearLayout.LayoutParams ?: return
        val h = if (top) topInset else 0
        if (lp.height != h) { lp.height = h; topSpacer.layoutParams = lp }
        rail.setPadding(rail.paddingLeft, (8 * dp).toInt() + if (top || railCompact) 0 else topInset, rail.paddingRight, rail.paddingBottom)
    }

    /** バーとページの境目の細い線 */
    private val divider = View(ctx)
    val saveStrip = SaveStrip(ctx, theme, saveQueue) { navigate(SiteConfig.QUEUE_URL) }
    /** 上部バー + タブの 2 段目 + 保存の帯 + 区切り線をまとめた箱 (スクロールで隠す) */
    val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(topSpacer, LinearLayout.LayoutParams(MATCH_PARENT, 0))
        addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, (52 * dp).toInt()))
        addView(tabRow, LinearLayout.LayoutParams(MATCH_PARENT, (40 * dp).toInt()))
        addView(saveStrip.view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(divider, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, (0.5f * dp).toInt())))
    }
    val scroll = HeaderScroll(header, dp)
    /** 左右に置くときの縦のバー (上に置くときは使わない)。[railBox] は、このバーと内容との境目の線を並べた箱 */
    private val rail = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding((4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt())
    }
    /** 縦のバーが画面の高さに収まらないとき (スマホの横向きでサイトが多いとき) は、縦に動かして、下のボタンにも届くようにする */
    private val railScroll = android.widget.ScrollView(ctx).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(rail, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
    /** 左右のバーを、ボタンの分の高さだけにするか (重ねて出すスマホ向け)。false なら画面の縦いっぱい。MainActivity が置き場所に合わせて決める */
    var railCompact = false
        set(v) { field = v; applyRailCompact() }

    private fun applyRailCompact() {
        railScroll.isFillViewport = !railCompact // 縦を埋めると、ボタンの分だけにならない
        applyTopInset() // ボタンの分だけのときは、中ほどに置くので、カメラの分の余白は要らない
        paintRailBg()
        val lp = railScroll.layoutParams as? LinearLayout.LayoutParams ?: return
        val h = if (railCompact) WRAP_CONTENT else MATCH_PARENT
        if (lp.height != h) { lp.height = h; railScroll.layoutParams = lp }
    }
    private val railDivider = View(ctx)

    /** 左右のバーの背景。重ねて出すとき ([overlayRail]) は、ページが透けて見える半透明の地色と影。ボタンの分だけのとき ([railCompact]) は、角の丸い浮かぶカード (境目の線は要らない)。ふつうに並べるときは地色なし (root の地色が見える) */
    private fun paintRailBg() {
        val card = overlayRail && railCompact
        railDivider.visibility = if (card) View.GONE else View.VISIBLE
        val tint = (theme.pal.bg and 0xFFFFFF)
        railBox.background = when {
            card -> android.graphics.drawable.GradientDrawable().apply {
                setColor(tint or ((cfg.barCardAlpha() * 255 / 100) shl 24)); cornerRadius = 20 * dp; setStroke(maxOf(1, (0.5f * dp).toInt()), theme.pal.divider)
            }
            overlayRail -> android.graphics.drawable.ColorDrawable(tint or 0xE8000000.toInt())
            else -> null
        }
        railBox.elevation = if (overlayRail) (if (card) 8 else 10) * dp else 0f
    }
    val railBox = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    /** 左右のバーを隠しているか (上のバーは [scroll] が持つ) */
    private var railHidden = false

    /** 上部バー左端の「再読み込み」。アプリ内の画面 (リスト・設定など) では見えなくする */
    private var reloadButton: View? = null
    /** 上部バー右端の「⋮」。リストなどアプリ内の画面 (設定を除く) では歯車になり、押すと設定を開く */
    private var menuButton: View? = null
    private var badge: TextView? = null
    /** 「⋮」/ 歯車の右上の、新しい版の点 */
    private var updateDotView: View? = null

    /** バーの中身を作り直す (表示するサイト・タブの並び・テーマ・画面の幅が変わったとき)。 */
    fun fill() {
        bar.removeAllViews()
        tabRow.removeAllViews()
        val specs = tabSpecs()
        applyTopInset()
        if (cfg.barPos() != "top") { fillSide(specs); return }
        bar.visibility = View.VISIBLE
        divider.visibility = View.VISIBLE
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
        menuButton = iconButton(R.drawable.ic_more, "メニュー", dot = true) { if (useGear()) navigate(SETTINGS_URL) else openMainMenu(it) }.also { bar.addView(it) }
        updateButtons()
        tabRow.visibility = if (twoRows) View.VISIBLE else View.GONE
        if (scroll.displaced) scroll.showNow() // 段数が変わるかもしれないので、作り直したときは出して測り直す (途中で止まったバーも)
        updateListCount()
    }

    /** 左右の縦のバーの中身 (再読み込み / サイトのタブ / すきま / リスト / メニュー)。header には保存の帯だけ残る。 */
    private fun fillSide(specs: List<SiteTabs.Spec>) {
        bar.visibility = View.GONE
        tabRow.visibility = View.GONE
        divider.visibility = View.GONE
        rail.removeAllViews()
        val centered = { v: View -> (v.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL; v }
        // ⋮ は、いちばん上。そのすぐ下に再読み込み (メニューも上から下へ開く)
        menuButton = iconButton(R.drawable.ic_more, "メニュー", dot = true) { if (useGear()) navigate(SETTINGS_URL) else openMainMenu(it) }.also { it.tag = SiteTabs.RAIL_TAG; rail.addView(centered(it)) } // 印: メニューをバーの横に出す (MainMenu)
        reloadButton = if (cfg.showReload()) iconButton(R.drawable.ic_refresh, "再読み込み") { reload() }.also { rail.addView(centered(it)) } else null
        val tinted = cfg.settings().optBoolean("tabsTinted")
        specs.forEach { sp ->
            rail.addView(tabs.railTab(sp.label, sp.color, tinted).also(sp.setup), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() })
        }
        rail.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f)) // 余った高さは、リストを下へ押しやる
        rail.addView(centered(iconButton(R.drawable.ic_book, "リスト", badge = true) { navigate(LIST_URL) }))
        railBox.removeAllViews()
        val line = (railDivider.apply { setBackgroundColor(theme.pal.divider) })
        val railW = (RAIL_DP * dp).toInt()
        if (cfg.barPos() == "left") {
            railBox.addView(railScroll, LinearLayout.LayoutParams(railW, MATCH_PARENT))
            railBox.addView(line, LinearLayout.LayoutParams(maxOf(1, (0.5f * dp).toInt()), MATCH_PARENT))
        } else {
            railBox.addView(line, LinearLayout.LayoutParams(maxOf(1, (0.5f * dp).toInt()), MATCH_PARENT))
            railBox.addView(railScroll, LinearLayout.LayoutParams(railW, MATCH_PARENT))
        }
        applyRailCompact()
        // ページの上に重ねて出すとき ([overlayRail]) は、ページが透けて見える半透明の地色と影を付ける。ふつうに並べるときは地色なし (root の地色が見える)
        paintRailBg()
        updateButtons()
        updateListCount()
        if (scroll.displaced) scroll.showNow()
    }

    /** 上のバーが、スクロールで途中まで隠れたまま止まっているか (hidden は false のまま) */
    val partlyHidden get() = onTop && scroll.displaced && !scroll.hidden

    /** 上のバーか (左右のバーなら false) */
    val onTop get() = cfg.barPos() == "top"

    /** いまバー (上でも左右でも) を隠しているか */
    val hidden get() = if (onTop) scroll.hidden else railHidden

    /** 左右のバーを、ページの上に半透明で重ねて出すか (「端からスワイプで出す」のとき)。重ねれば、出し入れでページの幅が変わらない。 */
    val overlayRail get() = !onTop && cfg.barEdge()

    /**
     * バーを出す / 隠す (アニメーションつき)。上のバーは [scroll] (高さを縮める)。
     * 左右のバー: 重ねて出すとき ([overlayRail]) は、横へ滑らせるだけ (ページは動かない)。ふつうに並べているときは、幅を少しずつ変える (ページも一緒に広がる・狭まる)。
     */
    fun setShown(shown: Boolean) {
        if (onTop) { scroll.setHidden(!shown); return }
        if (shown == !railHidden) return
        railHidden = !shown
        railAnim?.cancel()
        railBox.animate().cancel()
        if (overlayRail) {
            val w = (railBox.width.takeIf { it > 0 } ?: railFullPx()) + if (railCompact) (8 * dp).toInt() else 0 // 浮かぶカードは、端との間の余白の分も抜ける
            val off = if (cfg.barPos() == "left") -w.toFloat() else w.toFloat()
            if (shown) {
                railBox.translationX = off
                railBox.visibility = View.VISIBLE
                railBox.animate().translationX(0f).setDuration(RAIL_ANIM_MS).start()
            } else {
                railBox.animate().translationX(off).setDuration(RAIL_ANIM_MS).withEndAction { if (railHidden) railBox.visibility = View.GONE }.start()
            }
            return
        }
        val full = railFullPx()
        val from = if (railBox.visibility == View.VISIBLE) railBox.width else 0
        railBox.visibility = View.VISIBLE
        railAnim = ValueAnimator.ofInt(from, if (shown) full else 0).apply {
            duration = RAIL_ANIM_MS
            addUpdateListener { setRailWidth(it.animatedValue as Int, full) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) {
                    if (cancelled) return
                    if (shown) setRailWidth(WRAP_CONTENT, full) else railBox.visibility = View.GONE
                }
            })
            start()
        }
    }

    /** アニメーションなしですぐ出す (ページを開いたとき・バーを作り直したとき)。 */
    fun showNow() {
        if (onTop) { scroll.showNow(); return }
        railHidden = false
        railAnim?.cancel(); railBox.animate().cancel()
        railBox.translationX = 0f
        railBox.visibility = View.VISIBLE
        for (i in 0 until railBox.childCount) railBox.getChildAt(i).translationX = 0f
        setRailWidth(WRAP_CONTENT, railFullPx())
    }

    /** 隠している状態を、アニメーションなしですぐ作る (「端からスワイプで出す」をオンにしたとき・バーを作り直したとき)。 */
    fun hideNow() {
        if (onTop) { if (!scroll.hidden) scroll.setHidden(true) }
        else { railHidden = true; railAnim?.cancel(); railBox.animate().cancel(); railBox.visibility = View.GONE }
    }

    private var railAnim: ValueAnimator? = null
    private fun railFullPx() = (RAIL_DP * dp).toInt() + maxOf(1, (0.5f * dp).toInt())

    /** 左右のバーの箱の幅を w にし、中身を (その分だけ) 画面の外側へずらす。w = WRAP_CONTENT なら元の大きさ・ずらしなし。 */
    private fun setRailWidth(w: Int, full: Int) {
        val lp = railBox.layoutParams
        lp.width = w
        railBox.layoutParams = lp
        val shift = if (w == WRAP_CONTENT) 0f else (full - w).toFloat()
        val dx = if (cfg.barPos() == "left") -shift else shift // 左のバーは左へ、右のバーは右へ抜ける
        for (i in 0 until railBox.childCount) railBox.getChildAt(i).translationX = dx
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
    private fun iconButton(icon: Int, desc: String, badge: Boolean = false, dot: Boolean = false, onClick: (View) -> Unit): View {
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
        if (dot) { // 新しい版が見つかったとき、右上に小さな点 (見せるかは updateButtons)
            val d = (10 * dp).toInt()
            updateDotView = View(ctx).apply {
                background = theme.pill(BarTheme.ACCENT).apply { setStroke((2 * dp).toInt(), theme.pal.bg) }
                visibility = View.GONE
            }
            frame.addView(updateDotView, FrameLayout.LayoutParams(d + (4 * dp).toInt(), d + (4 * dp).toInt(), Gravity.TOP or Gravity.END).apply {
                topMargin = (6 * dp).toInt(); marginEnd = (6 * dp).toInt()
            })
        }
        return frame
    }

    private companion object { const val RAIL_DP = 76; const val RAIL_ANIM_MS = 180L } // 左右の縦のバーの幅・出し入れの時間

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
        updateDotView?.visibility = if (updateDot()) View.VISIBLE else View.GONE
        (menuButton as? FrameLayout)?.let { f ->
            f.contentDescription = if (gear) "設定" else "メニュー"
            (f.getChildAt(0) as? ImageView)?.setImageResource(if (gear) R.drawable.ic_settings else R.drawable.ic_more)
        }
    }

    /** リストのバッジ (リストの作品の件数。以前の版で読了の印が付いた作品は、読み込みで外れるまで数えない) を保存データから更新する。 */
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
        railDivider.setBackgroundColor(theme.pal.divider)
        paintRailBg()
        saveStrip.refresh(onQueuePage)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !theme.barDark
            isAppearanceLightNavigationBars = !theme.barDark
        }
    }
}
