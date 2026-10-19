package com.tonbo2339.yomikura

import org.json.JSONArray
import org.json.JSONObject

/**
 * ⋮ メニューの項目のうち、設定 (上部バー → メニューに出す項目) で出す / 出さないを選べるもの。項目の名前と初期値はここだけに書く
 * (設定画面は NHStore.menuItems() で読んで、スイッチを並べる)。Android に依存しない (単体テストで動かせる)。
 *
 * - 選んだ値は保存データの settings.menuItems = {key: true / false}。未設定の項目は初期値 ([Item.default])
 * - 上の段 (戻る・進む・再読み込み・先頭へ・末尾へ) と「設定」は、いつも出す (設定を消すと、戻せなくなるため)
 * - 以前の設定 menuShare = false (共有・URL のコピー・ほかのブラウザをまとめて外す) は、その 3 つを未設定のときだけ引き継ぐ
 */
object MenuItems {
    /** group = 設定画面の見出し */
    data class Item(val key: String, val label: String, val group: String, val default: Boolean)

    const val LATER = "later"
    const val SAVE = "save"
    const val RANKING = "ranking"
    const val READ_ALOUD = "readAloud"
    const val AUTO_SCROLL = "autoScroll"
    const val FIND = "find"
    const val SHARE = "share"
    const val COPY_URL = "copyUrl"
    const val OPEN_BROWSER = "openBrowser"
    const val DIAGNOSE = "diagnose"

    val ALL = listOf(
        Item(LATER, "後で読むに追加・リストから削除", "作品", true),
        Item(SAVE, "端末に保存", "作品", true),
        Item(RANKING, "ランキングの行き先に設定", "作品", true),
        Item(READ_ALOUD, "読み上げ", "読書の補助", true),
        Item(AUTO_SCROLL, "自動スクロール", "読書の補助", true),
        Item(FIND, "ページ内を検索", "ページ", true),
        Item(SHARE, "共有", "ページ", true),
        Item(COPY_URL, "URL をコピー", "ページ", true),
        Item(OPEN_BROWSER, "ほかのブラウザで開く", "ページ", true),
        Item(DIAGNOSE, "このページの表示を診断", "不具合の調査", false),
    )

    private val LEGACY_SHARE = setOf(SHARE, COPY_URL, OPEN_BROWSER)

    /** その項目をメニューに出すか。settings = 保存データの settings。知らない項目は出す */
    fun on(settings: JSONObject, key: String): Boolean {
        val item = ALL.firstOrNull { it.key == key } ?: return true
        val chosen = settings.optJSONObject("menuItems")
        if (chosen != null && chosen.has(key)) return chosen.optBoolean(key, item.default)
        if (key in LEGACY_SHARE && !settings.optBoolean("menuShare", true)) return false
        return item.default
    }

    /** 設定画面に渡す一覧: [{key, label, group, default, on}] (並びは ALL の順) */
    fun toJson(settings: JSONObject): JSONArray = JSONArray().also { a ->
        ALL.forEach {
            a.put(JSONObject().put("key", it.key).put("label", it.label).put("group", it.group).put("default", it.default).put("on", on(settings, it.key)))
        }
    }
}
