package com.tonbo2339.yomikura

import android.webkit.JavascriptInterface

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
}
