package com.tonbo2339.yomikura

import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tonbo2339.yomikura.SiteConfig.COLLECT_PAGES
import com.tonbo2339.yomikura.SiteConfig.LIST_URL
import com.tonbo2339.yomikura.SiteConfig.LOGIN_PAGES

/**
 * 更新の確認を、画面を切り替えずに行う (CollectSession の WebView で巡回) 画面側の進行役: 始める・止める・進み具合・結果のお知らせ。
 * 確認画面 (Cloudflare など) で止まったサイトは、全画面で出して通してもらうか尋ねる。巡回そのものは CollectSession / Collector、
 * 結果の文とボタンの決め方は CollectSummary、手動の 10 分制限は CollectCooldown。画面の操作は [Ui] を通す。
 */
class CollectController(
    private val activity: AppCompatActivity,
    private val cfg: AppSettings,
    private val hostFilter: HostFilter,
    private val ui: Ui,
) {
    interface Ui {
        fun snack(text: String, action: Pair<String, () -> Unit>? = null)
        fun navigate(url: String)
        /** アプリ内ページが出ているか (UI スレッドから) */
        fun onAppPage(): Boolean
        /** ページの JS を、画面の WebView でそのまま実行する */
        fun eval(script: String)
        /** 確認で保存データが変わった: リストのバッジを直す */
        fun listCountChanged()
    }

    /** 画面に出さない更新の確認 (「更新」ボタン / 起動時の自動確認)。確認中だけある (確認画面で待っているサイトがあれば、終わったあとも残る) */
    private var session: CollectSession? = null
    /** 確認画面で止まったサイトを、全画面で出して通してもらう画面と、その前の問いかけ */
    private val challengeScreen by lazy { ChallengeScreen(activity) }
    private var prompt: AlertDialog? = null
    /** prompt がどのサイトの問いかけか */
    private var promptSite: String? = null
    /** 確認画面を通して終わったサイトの「完了しました」(次の結果の知らせに付ける) */
    private var doneNote: String? = null

    @Volatile var running = false
        private set
    /** 確認の進み具合 (「2 / 4」)。リストを後から開いたときにも出せるように持っておく */
    @Volatile var progress = ""
        private set

    /** 更新の確認を始める。auto = 起動時の自動確認: 何も無ければ黙っている。 */
    fun start(auto: Boolean) {
        // この画面の確認か、裏での確認 (BgCheckJob) が進んでいる間は始めない
        if (Collector.crawling()) {
            if (!auto) ui.snack("更新を確認しています…")
            return
        }
        // 手動の確認は、前回の確認 (途中で止まったものも) から 10 分は始めない (サイトへ続けてアクセスしないため)
        if (!auto) {
            val wait = CollectCooldown.remainingMs(CollectCooldown.lastOf(cfg.data()), System.currentTimeMillis())
            if (wait > 0) {
                ui.snack("続けて確認できるのは、前回から ${CollectCooldown.MINUTES} 分後です (あと ${CollectCooldown.remainingMinutes(wait)} 分)")
                return
            }
        }
        val startUrl = SiteConfig.collectStartUrl(cfg.data(), if (auto) "fg-auto" else "fg")
        if (startUrl == null) {
            if (!auto) {
                // 対象のサイトはあるが、どれもアクセスを止められて休んでいる (SiteRest)
                ui.snack(if (SiteConfig.collectAnyActive(cfg.data())) "アクセスを止められたサイトを休ませています。しばらくしてからお試しください"
                    else "更新を確認するサイトがありません。設定の「更新の確認」で選べます")
            }
            return
        }
        stop() // 前回、確認画面で待ったまま残っているもの
        CollectCooldown.markStarted(activity)
        session = CollectSession(
            activity, hostFilter, startUrl,
            onProgress = { i, n ->
                progress = "$i / $n"
                if (running) ui.eval("window.nhProgress && nhProgress($i,$n)")
            },
            onChallenge = ::askChallenge,
            onHeldEnd = { site, ok -> closeChallenge(site, ok) },
        ) { log ->
            setRunning(false)
            showResult(log, auto)
        }
        setRunning(true)
    }

    /** 画面での確認を全部やめる。WebView を壊す前に、それを出している画面と問いかけを閉じる */
    fun stop() {
        doneNote = null
        dismissPrompt()
        challengeScreen.dismiss()
        session?.cancel()
        session = null
    }

    /** 裏の確認 (アプリを開く前から動いていた分) で確認画面が出たとき: サイトのページを開くか尋ねる */
    fun offerOpenSite(site: String) =
        promptChallenge(site, "サイトを開いて通してください。通したあと、もう一度更新を確認してください。", "開く") { ui.navigate(COLLECT_PAGES[site] ?: LIST_URL) }

    /** 確認中の表示: リスト画面の更新アイコンを回す (終わったら描き直す)。 */
    private fun setRunning(on: Boolean) {
        val changed = running != on
        running = on
        if (!on) progress = ""
        if (changed) ui.eval("window.nhCollecting && nhCollecting($on)")
    }

    /**
     * サイトが確認画面で止まった: 画面に出して通してもらうか尋ねる。ほかのサイトの確認は、そのまま続いている。
     * 通れば、その WebView が続きを取得して、結果が加わる。
     */
    private fun askChallenge(site: String, held: Collector) =
        promptChallenge(site, "画面を出して通してください。ほかのサイトの確認は続けています。", "画面を出す") { showChallenge(site, held) }

    private fun showChallenge(site: String, held: Collector) {
        val web = held.view ?: return ui.snack("確認画面は、すでに終わりました")
        challengeScreen.show(site, BgCheck.siteName(site) + " の確認画面", web)
    }

    /** 「(サイト) で確認画面が出ました」の問いかけ。前の問いかけは閉じる */
    private fun promptChallenge(site: String, message: String, positive: String, onPositive: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        prompt?.dismiss()
        // 読書中などアプリ内ページでないときは、読みの邪魔にならないよう、ほかのお知らせと同じ下の表示にする
        if (!ui.onAppPage()) {
            ui.snack("${BgCheck.siteName(site)} で確認画面が出ました。$message", positive to onPositive)
            return
        }
        promptSite = site
        prompt = AlertDialog.Builder(activity)
            .setTitle("${BgCheck.siteName(site)} で確認画面が出ました")
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onPositive() }
            .setNegativeButton("あとで", null)
            .show()
    }

    /** 確認画面で待っていたサイトの巡回が終わった (通った / 時間切れ): そのサイトの問いかけと画面を閉じる (ほかのサイトのものは残す) */
    private fun closeChallenge(site: String, ok: Boolean) {
        if (ok) doneNote = BgCheck.siteName(site) + " の確認が完了しました"
        if (promptSite == site) dismissPrompt()
        challengeScreen.dismiss(site)
    }

    private fun dismissPrompt() {
        prompt?.dismiss()
        prompt = null
        promptSite = null
    }

    /** 確認の結果を出す: 「N 作品の更新をリストに追加しました」「更新作品はありませんでした」と、確認できなかったサイト。 */
    private fun showResult(log: String?, auto: Boolean) {
        ui.listCountChanged()
        if (log == null) {
            // 「もう一度」は出さない: 止まった確認も 10 分の待ちに数える (CollectCooldown.lastOf) ので、すぐには押せないため
            if (!auto) ui.snack("更新を確認できませんでした (ページが進みませんでした)。${CollectCooldown.MINUTES} 分ほどたってから、もう一度お試しください")
            return
        }
        // 文とボタンは CollectSummary が決める。開く先の URL はここで決める
        val sum = CollectSummary.of(BgCheck.parse(log), auto, ui.onAppPage()) ?: return
        val action: Pair<String, () -> Unit>? = when (val act = sum.action) {
            is CollectSummary.Action.OpenSite -> "開く" to {
                // 確認画面で待っている WebView があればそれを出す。無ければ (時間切れなど) サイトのページを開く
                val held = session?.heldFor(act.site)
                if (held != null) showChallenge(act.site, held) else ui.navigate(COLLECT_PAGES[act.site] ?: LIST_URL)
            }
            is CollectSummary.Action.Login -> "ログイン" to { ui.navigate(LOGIN_PAGES[act.site] ?: LIST_URL) }
            CollectSummary.Action.List -> "リスト" to { ui.navigate(LIST_URL) }
            null -> null
        }
        // 確認画面を通して取得まで終わったサイトがあれば、結果の前に「完了しました」を付ける
        val note = doneNote?.let { it + "\n" }.orEmpty()
        doneNote = null
        ui.snack(note + sum.text, action)
    }
}
