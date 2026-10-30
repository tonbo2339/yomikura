package com.tonbo2339.yomikura

/**
 * 画面で行った更新の確認の結果 (BgCheck.Result) から、下に出すお知らせの文と、付けるボタンを決める。Android に依存しない (単体テストで動かせる)。
 * 開く先の URL は画面 (CollectController) が決める。ここはどのボタンを付けるか ([Action]) だけを返す。
 */
object CollectSummary {
    /** お知らせのボタン: 確認画面で止まったサイトを開く / ログインしていないサイトのログイン画面 / リスト */
    sealed class Action {
        data class OpenSite(val site: String) : Action()
        data class Login(val site: String) : Action()
        data object List : Action()
    }

    data class Summary(val text: String, val action: Action?)

    /**
     * auto = 起動時の自動確認 (新しい話も確認画面も無ければ、何も出さない = null)。onAppPage = いまアプリ内ページ (リストなど) を開いているか。
     * 確認画面 (Cloudflare) は、アプリの画面でサイトを開いて通してもらう (自動では通さない)
     */
    fun of(r: BgCheck.Result, auto: Boolean, onAppPage: Boolean): Summary? {
        if (auto && r.added == 0 && r.fresh.isEmpty() && r.challenge.isEmpty()) return null
        val head = when {
            r.added > 0 -> "${r.added} 作品の更新をリストに追加しました"
            r.fresh.isNotEmpty() -> "${r.fresh.size} 作品に新しい話があります"
            else -> "新しい話のある作品はありませんでした"
        }
        val stuck = r.challenge.firstOrNull()
        val tail = when {
            stuck != null -> "\n" + r.challenge.joinToString("・", transform = BgCheck::siteName) + " は確認画面で止まりました。「開く」で通してください"
            r.errors.isNotEmpty() -> "\n確認できなかった: " + r.errors.joinToString("、")
            else -> ""
        }
        val action = when {
            stuck != null -> Action.OpenSite(stuck)
            // ログイン切れ (または未ログイン): そのサイトのログイン画面へ。ログインしたら、もう一度更新を確認してもらう
            r.login.isNotEmpty() -> Action.Login(r.login.first())
            (r.added > 0 || r.fresh.isNotEmpty()) && !onAppPage -> Action.List
            else -> null
        }
        return Summary(head + tail, action)
    }
}
