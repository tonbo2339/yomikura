package com.tonbo2339.yomikura

import android.webkit.JavascriptInterface
import org.json.JSONArray

/**
 * JS から使う窓口 (NHFonts): 自分で入れたフォントの一覧・追加・名前の変更・削除。アプリ内ページからだけ受け付ける (サイトのページには、入れたフォントの名前を見せない)。
 * フォントの中身を返すのは窓口ではなく NovelClient (https://yomikura.local/font/<id>)。JS からは名前と引数の数で呼ばれるので、メソッドの名前と形は変えない。
 */
class FontBridge(host: BridgeHost, private val fonts: CustomFonts, private val files: FontFiles) : GuardedBridge(host) {
    /** [{id, name}] の JSON */
    @JavascriptInterface
    fun list(): String = forApp("[]") { fonts.toJson() }

    /** ファイルを選ぶ画面を出す。結果は画面の nhFontResult(JSON) へ */
    @JavascriptInterface
    fun pick() = onUiForApp { files.startPick() }

    /** 一覧に出す名前を変える (英語の内部名は変わらない) */
    @JavascriptInterface
    fun rename(id: String, name: String): Boolean = forApp(false) { fonts.rename(id, name) }

    @JavascriptInterface
    fun remove(id: String): Boolean = forApp(false) { fonts.remove(id) }

    // ---- サイトの話ページの TT (表示の設定) から使う: 名前の一覧と、選ぶだけ。ファイルの id は見せない (ページの広告・解析の JS から見えるため)。
    // 合言葉つきで、対応サイトかアプリ内ページから ----

    /** 追加したフォントの名前の並び (["名前", ...]。JSON)。id は含めない。並びの番号を [choose] に渡す */
    @JavascriptInterface
    fun names(t: String?): String = if (trusted(t)) fonts.list().let { l -> JSONArray().also { a -> l.forEach { a.put(it.name) } }.toString() } else "[]"

    /** 並びの番号 [index] の追加フォントを選ぶ (設定 font を custom:<id> にする)。-1 なら、追加フォントを選んでいない状態 (呼び出し側が設定を書く)。選べたら true */
    @JavascriptInterface
    fun choose(index: Int, t: String?): Boolean {
        if (!trusted(t)) return false
        val f = fonts.list().getOrNull(index) ?: return false
        NHData.update(ctx) { d -> NHData.settingsForWrite(d).put("font", "custom:" + f.id) }
        onUi { host.onDataChanged() }
        return true
    }

    /** いま選んでいる追加フォントの並びの番号 (無ければ -1) */
    @JavascriptInterface
    fun current(t: String?): Int {
        if (!trusted(t)) return -1
        val id = FontSelection.idOf(NHData.settings(ctx).optString("font")).orEmpty()
        return fonts.list().indexOfFirst { it.id == id }
    }
}
