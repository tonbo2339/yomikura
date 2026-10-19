package com.tonbo2339.yomikura

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/**
 * 画面側 (上部バー・メニュー・シート・お知らせ) の見た目の決まり: 配色 (ライト / ダーク)・サイトの色・寸法の単位・押したときの形。
 * 色や形は、ここだけを直せば全部に効く。ほかの部品は BarTheme を受け取って、色を直接持たない。
 * 上部バーは暖かみのある黒 + 緑のアクセント。iOS のナビゲーションバー風に、ライトは明るい地 + 細い区切り線、ダークは黒系。サイトは色の点で見分ける。
 */
class BarTheme(private val ctx: Context) {

    data class Palette(val bg: Int, val divider: Int, val text: Int, val subtext: Int, val ripple: Int)

    /** dp → px の倍率 */
    val dp: Float by lazy { ctx.resources.displayMetrics.density }

    /** 端末のダークテーマか (アプリの設定 theme を含めて、いまの構成で判定する) */
    val isNight: Boolean
        get() = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    val pal: Palette
        get() = if (isNight) DARK else LIGHT

    /** 押したときのリップル (mask の形に収まる)。 */
    fun ripple(mask: GradientDrawable, content: GradientDrawable? = null) =
        RippleDrawable(ColorStateList.valueOf(pal.ripple), content, mask)

    /** 角を丸めた一色の形 (丸いボタン・色の点・バッジ)。 */
    fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 999 * dp }

    /** 下から出る選択のシートの配色。 */
    fun sheetColors() = BottomSheet.Colors(pal.bg, pal.divider, pal.text, pal.subtext, pal.ripple)

    /** メニュー (⋮・サイトのタブ) を、いまの配色で作る。 */
    fun menu(): MainMenu = pal.let { MainMenu(ctx, dp, it.bg, it.text, it.subtext, it.divider, it.ripple) }

    companion object {
        private val LIGHT = Palette(0xFFF9F9F9.toInt(), 0xFFC6C6C8.toInt(), 0xFF1C1C1E.toInt(), 0x993C3C43.toInt(), 0x1F000000)
        private val DARK = Palette(0xFF1C1C1E.toInt(), 0xFF38383A.toInt(), 0xFFF2F2F7.toInt(), 0x99EBEBF5.toInt(), 0x33FFFFFF)

        /** 緑のアクセント (読み込みの線・バッジ・保存中・ボタン) */
        const val ACCENT = 0xFF2F8F5B.toInt()
        /** 保存の帯: 待ち・一時停止 */
        const val SAVE_WAIT = 0xFFE0A030.toInt()
        /** 保存の帯: 確認が必要 */
        const val SAVE_ALERT = 0xFFD04A3A.toInt()
        /** タブの背景をサイトの色で塗るときの濃さ (アルファ) */
        const val TINT_ALPHA = 0x2E000000

        /** サイトの色 (タブの点・シートの見出し)。設定で選んだ色が無いときの既定。 */
        const val NAROU = 0xFF4A8FE0.toInt()
        const val R18 = 0xFFD9529A.toInt()
        const val HAMELN = 0xFFE07B3C.toInt()
        const val KAKUYOMU = 0xFF22B3A4.toInt()

        /** サイトのキー → 既定の色 (R18 の 3 サイトは共通) */
        fun siteColor(key: String): Int = when (key) {
            "narou" -> NAROU
            "hameln" -> HAMELN
            "kakuyomu" -> KAKUYOMU
            else -> R18
        }
    }
}
