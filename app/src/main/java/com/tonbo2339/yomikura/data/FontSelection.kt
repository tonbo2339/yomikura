package com.tonbo2339.yomikura

import android.content.Context

/**
 * 設定の font (`site` / `gothic` / `mincho` / `custom:<id>`) のうち、自分で入れたフォントの選び方。
 * 設定の写し (自動バックアップ) にはフォントのファイルが入らないので、入れ直したあとなどは、選んだ id のファイルが無いことがある。
 */
object FontSelection {
    private val CUSTOM = Regex("^custom:([0-9a-f]{12})$")

    /** font が自分で入れたフォントなら、その id。違えば null */
    fun idOf(font: String?): String? = font?.let { CUSTOM.matchEntire(it)?.groupValues?.get(1) }

    /** 入っていないフォントが選ばれているときは、サイト標準に戻す (戻さないと、サイトの本文だけゴシックのままになる)。起動のとき、ページを開く前に呼ぶ */
    fun repair(ctx: Context, fonts: CustomFonts) {
        val id = idOf(NHData.settings(ctx).optString("font")) ?: return
        if (fonts.file(id) != null) return
        NHData.update(ctx) { d -> NHData.settingsForWrite(d).put("font", "site") }
    }
}
