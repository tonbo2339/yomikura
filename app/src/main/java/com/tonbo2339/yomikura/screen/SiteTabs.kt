package com.tonbo2339.yomikura

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat

/**
 * 上部バーのサイトのタブ 1 つの見た目と、その幅の測り方。どのタブを何段に並べるかは TopBar が決める。
 * タブ: 色の点 + 名前 + ▾。compact (幅が足りないとき) は ▾ と余白を省き、名前の文字を幅に合わせて縮める。
 * 余白・点・▾ の寸法は [tab] と [need] で合わせる (片方だけ変えると、幅の見積もりがずれる)。
 */
class SiteTabs(private val ctx: Context, private val theme: BarTheme) {
    private val dp = theme.dp

    /** タブ 1 つぶんの内容: 名前・点の色・押したときの動作の設定。 */
    class Spec(val key: String, val label: String, val color: Int, val setup: (LinearLayout) -> Unit)

    /**
     * タブ 1 つの View。tinted なら、タブの背景をサイトの色で薄く塗る。
     * fixedSp > 0 (1 段に固定して幅をそろえたとき) は、全タブ同じ文字の大きさにして、入りきらなければ 2 行まで折り返す。
     */
    fun tab(label: String, dot: Int, weight: Float, compact: Boolean, tinted: Boolean, fixedSp: Float = 0f): LinearLayout {
        val pad = ((if (compact) 3 else 6) * dp).toInt()
        val dotSize = ((if (compact) 5 else 7) * dp).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(pad, 0, pad, 0)
            background = theme.ripple(theme.pill(Color.WHITE), if (tinted) theme.pill((dot and 0xFFFFFF) or BarTheme.TINT_ALPHA) else null)
            isClickable = true
            addView(View(ctx).apply { background = theme.pill(dot) },
                LinearLayout.LayoutParams(dotSize, dotSize).apply { marginEnd = pad })
            val name = TextView(ctx).apply {
                text = label
                textSize = 13f
                typeface = MEDIUM
                setTextColor(theme.pal.text)
                maxLines = 1
            }
            if (fixedSp > 0) {
                name.textSize = fixedSp
                name.maxLines = 2
                name.ellipsize = TextUtils.TruncateAt.END // 2 行でも入りきらない長い名前は「…」で省く
                name.setLineSpacing(0f, 0.9f)
                addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            } else if (compact) {
                // 自動縮小は高さが決まっていないと効かないので、タブの高さいっぱいにして縦は中央に置く
                name.gravity = Gravity.CENTER_VERTICAL
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(name, 9, 13, 1, TypedValue.COMPLEX_UNIT_SP)
                addView(name, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            } else {
                addView(name)
                addView(TextView(ctx).apply {
                    text = "▾"
                    textSize = 10f
                    setTextColor(theme.pal.subtext)
                    setPadding((3 * dp).toInt(), 0, 0, 0)
                })
            }
            layoutParams = LinearLayout.LayoutParams(0, (36 * dp).toInt(), weight).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = (2 * dp).toInt(); marginEnd = (2 * dp).toInt()
            }
        }
    }

    /** タブ 1 つを縮めずに出すのに要る幅 (px)。[tab] の余白・点・▾ の寸法と合わせる。 */
    fun need(label: String, compact: Boolean): Float {
        // 余白 (左右) + 点 + 点の右 + タブの外側の余白 (左右)
        var w = textWidth(label, 13f) + (if (compact) 3 + 3 + 5 + 3 + 2 + 2 else 6 + 6 + 7 + 6 + 2 + 2) * dp
        if (!compact) w += textWidth("▾", 10f, medium = false) + 3 * dp
        return w + 1 // 端数の切り捨てで 1px 足りなくならないように
    }

    /** 1 段に固定して幅をそろえたときの文字の大きさと各タブの文字 (計算は TabLayout)。 */
    fun oneRowText(labels: List<String>, tabWidth: Float): Pair<Float, List<String>> {
        val room = (tabWidth - (need("", true) - 1)) * 0.92f // 端数や字間で 1 文字はみ出さないよう、少し余裕をみる
        return TabLayout.oneRowText(labels, room) { text, sp -> textWidth(text, sp) }
    }

    /** 文字の幅 (px)。 */
    private fun textWidth(text: String, sp: Float, medium: Boolean = true): Float = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        if (medium) typeface = MEDIUM
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, ctx.resources.displayMetrics)
    }.measureText(text)

    private companion object {
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
