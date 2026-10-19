package com.tonbo2339.yomikura

import android.view.View
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.tonbo2339.yomikura.SiteConfig.SETTINGS_URL
import org.json.JSONObject

/**
 * 右端のメニュー (⋮。サイトのページだけ。リストなどアプリ内の画面では歯車、設定の画面では何も出さない) の中身を決めて出す。
 * Chrome のように、上段に 戻る・進む・再読み込み・先頭へ・末尾へ、下段に作品の追加・ランキングの設定・ページ内検索・共有・設定。
 * 「この作品を後で読むに追加」は作品 / 話のページだけ、「このページをランキングに設定」はランキングのページだけ出す (それ以外では項目ごと消す)。
 * 戻れない・進めない項目は薄く出す。見た目は MainMenu、項目の出し分けの設定は MenuItems。
 */
class PageMenu(
    private val activity: AppCompatActivity,
    private val theme: BarTheme,
    private val cfg: AppSettings,
    private val webView: () -> WebView,
    private val aids: ReadingAids,
    private val pageActions: PageActions,
    private val ranking: RankingTarget,
    private val findInPage: () -> Unit,
    private val navigate: (String) -> Unit,
) {
    /** メニューを出す。作品のページなら、リストにあるか (「後で読むに追加」/「リストから削除」) と端末に保存してあるかを、ページに聞いてから出す。 */
    fun show(anchor: View) {
        val web = webView()
        if (SiteUrls.isWorkUrl(web.url)) {
            web.evaluateJavascript("window.nhWorkState ? nhWorkState() : null") { r ->
                val work = r.toJsonOrNull()
                // 自動スクロール中か (メニューの項目名を「止める」にするため)
                web.evaluateJavascript("window.nhAutoScrollOn ? nhAutoScrollOn() : false") { a ->
                    if (!activity.isFinishing && !activity.isDestroyed) show(anchor, work, a == "true") // 待つ間に画面が閉じていたら出さない (落ちないように)
                }
            }
        } else show(anchor, null, false)
    }

    /** work = ページの nhWorkState() ({inList, saved, url})。作品のページでなければ null */
    private fun show(anchor: View, work: JSONObject?, autoScrolling: Boolean) {
        val web = webView()
        val url = web.url
        val inList = work?.optBoolean("inList") == true
        val rankKey = ranking.siteOf(url)
        val onSite = url != null && (url.startsWith("http://") || url.startsWith("https://"))
        val onWork = SiteUrls.isWorkUrl(url)
        fun action(icon: Int, label: String, enabled: Boolean = true, run: () -> Unit) = MainMenu.Action(icon, label, enabled, run)
        // 下段の項目は、設定 (上部バー → メニューに出す項目) で出すと選んだものだけ (MenuItems)
        val st = cfg.settings()
        fun shown(key: String) = MenuItems.on(st, key)
        theme.menu().show(
            anchor,
            top = listOf(
                action(R.drawable.ic_arrow_back, "戻る", web.canGoBack()) { web.goBack() },
                action(R.drawable.ic_arrow_forward, "進む", web.canGoForward()) { web.goForward() },
                action(R.drawable.ic_refresh, "再読み込み") { web.reload() },
                action(R.drawable.ic_vertical_align_top, "先頭へ") { web.pageUp(true) },
                action(R.drawable.ic_vertical_align_bottom, "末尾へ") { web.pageDown(true) },
            ),
            groups = listOf(
                // 作品のページ・ランキングのページだけ出す (それ以外のページでは項目ごと出さない)
                buildList {
                    if (shown(MenuItems.LATER)) {
                        if (inList) add(action(R.drawable.ic_close, "この作品をリストから削除") { removeCurrentFromList() })
                        else if (onWork) add(action(R.drawable.ic_bookmark_add, "この作品を後で読むに追加") { addCurrentToList() })
                    }
                    // オフライン保存 (作品のページを #nh-save 付きで開くと、リストからの保存と同じ画面になる)
                    work?.optString("url")?.takeIf { shown(MenuItems.SAVE) && it.startsWith("https://") }?.let { workUrl ->
                        val label = if (work.optBoolean("saved")) "端末の保存を更新" else "この作品を端末に保存"
                        add(action(R.drawable.ic_download, label) { navigate("$workUrl#nh-save") })
                    }
                    // ランキングの行き先がこのページなら「初期設定に戻す」
                    if (rankKey != null && shown(MenuItems.RANKING)) {
                        if (ranking.isHere(rankKey, url)) add(action(R.drawable.ic_trophy, "ランキングの行き先を初期設定に戻す") { ranking.reset(rankKey) })
                        else add(action(R.drawable.ic_trophy, "このページをランキングに設定") { ranking.setHere(rankKey) })
                    }
                },
                // 読書の補助: 作品のページだけ (本文の読み上げ・ゆっくり下へ進む自動スクロール)
                // 読み上げ中・自動スクロール中は、オフにしていても「止める」を出す (止められなくならないように)
                if (onWork) buildList {
                    if (shown(MenuItems.READ_ALOUD) || aids.speaking) add(action(R.drawable.ic_read_aloud, if (aids.speaking) "読み上げを止める" else "読み上げ") { aids.toggleReadAloud() })
                    if (shown(MenuItems.AUTO_SCROLL) || autoScrolling) add(action(R.drawable.ic_auto_scroll, if (autoScrolling) "自動スクロールを止める" else "自動スクロール") { aids.toggleAutoScroll() })
                } else emptyList(),
                buildList {
                    if (shown(MenuItems.FIND)) add(action(R.drawable.ic_search, "ページ内を検索") { findInPage() })
                    if (shown(MenuItems.SHARE)) add(action(R.drawable.ic_share, "共有", onSite) { pageActions.share() })
                    if (shown(MenuItems.COPY_URL)) add(action(R.drawable.ic_copy, "URL をコピー", onSite) { pageActions.copyUrl() })
                    if (shown(MenuItems.OPEN_BROWSER)) add(action(R.drawable.ic_open_browser, "ほかのブラウザで開く", onSite) { pageActions.openInBrowser() })
                },
                buildList {
                    if (shown(MenuItems.DIAGNOSE)) add(action(R.drawable.ic_bug, "このページの表示を診断", onSite) { pageActions.diagnose() })
                    add(action(R.drawable.ic_settings, "設定") { navigate(SETTINGS_URL) })
                },
            ).filter { it.isNotEmpty() },
        )
    }

    /** 今開いている作品を「後で読む」に追加する (ページ側の nhAddCurrent を呼ぶ)。 */
    private fun addCurrentToList() {
        webView().evaluateJavascript("window.nhAddCurrent ? nhAddCurrent() : ''") { r ->
            val msg = when (r?.trim('"')) {
                "added" -> "「後で読む」に追加しました"
                "exists" -> "すでにリストにあります"
                "failed" -> "作品の情報を読み取れなかったので、追加していません"
                else -> "作品のページではありません。作品か話のページで押してください"
            }
            Messages.show(activity, msg)
        }
    }

    private fun removeCurrentFromList() {
        webView().evaluateJavascript("window.nhRemoveCurrent ? nhRemoveCurrent() : ''") { r ->
            Messages.show(activity, if (r?.trim('"') == "removed") "リストから削除しました" else "リストにありませんでした")
        }
    }
}
