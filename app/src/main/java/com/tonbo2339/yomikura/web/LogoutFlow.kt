package com.tonbo2339.yomikura

import org.json.JSONObject

/**
 * 設定のログインの画面の「ログアウト」: サイトの公式のログアウトを裏で行い、そのあとアプリの Cookie も消す。
 * 結果は nhLogoutResult({site, ok, message, page}) で画面へ。page = 失敗したときに画面で開く公式のページ (押すのは利用者)。
 * 公式の手順 (OfficialLogout) と Cookie (LoginCookies) の組み合わせだけを持ち、アプリ内ページからだけ受け付ける確認は呼ぶ側 (StoreBridge) が行う。
 */
class LogoutFlow(private val host: BridgeHost) {

    /**
     * 動いている途中か、終わってすぐ (OfficialLogout.waitSeconds) のときは始めず、nhLogoutResult({site, busy: true, wait}) を返す。
     * 公式の手順が無いサイトは、アプリの Cookie だけ消して成功を返す。
     */
    fun logout(site: String) {
        val page = OfficialLogout.pageOf(site)
        if (page == null) {
            LoginCookies.expire(site)
            return emit(result(site).put("ok", true).put("message", ""))
        }
        val started = OfficialLogout.run(host.activity, site) { ok, msg ->
            // 公式の結果にかかわらず、アプリの Cookie は消す (待っている間にほかの画面へ移っていても消す)
            LoginCookies.expire(site)
            emit(result(site).put("ok", ok).put("message", msg).put("page", page))
        }
        // 動いている途中か、終わってすぐ: 始めずに、待つ秒数を返す (画面はボタンを押せないままにする)
        if (!started) emit(result(site).put("busy", true).put("wait", OfficialLogout.waitSeconds(site)))
    }

    private fun result(site: String) = JSONObject().put("site", site)
    private fun emit(json: JSONObject) = host.emitJs("nhLogoutResult", json)
}
