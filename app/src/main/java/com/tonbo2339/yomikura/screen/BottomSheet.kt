package com.tonbo2339.yomikura

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 画面の下から出る選択のシート (アプリ内ページのボトムシートと同じ見た目: 角丸・つまみ・大きめのボタン)。見た目を作るだけで、何を出すかは呼ぶ側が決める。
 * 外側をタップするか戻るで閉じる ([show] の onCancel)。ボタンを押すとシートを閉じてから動作する。
 * 色はテーマ (ライト / ダーク) に合わせて呼ぶ側が渡す。accent = 目立たせるボタンの色
 */
class BottomSheet(
    private val ctx: Context,
    private val dp: Float,
    private val palette: Colors,
    private val accent: Int,
) {
    /** bg = シートの背景、divider = つまみ、text / subtext = 文字、ripple = 押したときの波紋 */
    data class Colors(val bg: Int, val divider: Int, val text: Int, val subtext: Int, val ripple: Int)

    /** ボタンの種類: 目立たせる / 控えめな塗り / 文字だけ */
    enum class Style { PRIMARY, TONAL, PLAIN }

    private val dialog = Dialog(ctx)
    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(10), px(20), px(16))
        val r = 16 * dp
        background = GradientDrawable().apply { setColor(palette.bg); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        // つまみ
        addView(View(ctx).apply { background = pill(palette.divider) },
            LinearLayout.LayoutParams(px(36), px(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = px(16) })
    }

    private fun px(v: Int) = (v * dp).toInt()
    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 999 * dp }

    /** 見出し: サイト (名前, 点の色) と問いかけ */
    fun header(site: Pair<String, Int>, title: String) {
        body.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(ctx).apply { background = pill(site.second) },
                LinearLayout.LayoutParams(px(8), px(8)).apply { marginEnd = px(6) })
            addView(TextView(ctx).apply { text = "${site.first}の作品"; textSize = 13f; setTextColor(palette.subtext) })
        })
        body.addView(TextView(ctx).apply {
            text = title
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(palette.text)
            setPadding(0, px(4), 0, px(14))
        })
    }

    /** ボタン 1 つ (sub は 2 行目の小さい文字) */
    fun button(label: String, sub: String?, style: Style, onClick: () -> Unit) {
        val fg = when (style) { Style.PRIMARY -> Color.WHITE; Style.TONAL -> accent; Style.PLAIN -> palette.text }
        val fill = when (style) { Style.PRIMARY -> accent; Style.TONAL -> (accent and 0xFFFFFF) or 0x24000000; Style.PLAIN -> Color.TRANSPARENT }
        val shape = { c: Int -> GradientDrawable().apply { setColor(c); cornerRadius = 14 * dp } }
        body.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = px(54)
            setPadding(px(16), px(10), px(16), px(10))
            background = RippleDrawable(ColorStateList.valueOf(palette.ripple), shape(fill), shape(Color.WHITE))
            isClickable = true
            addView(TextView(ctx).apply {
                text = label; textSize = 16f; setTextColor(fg); gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            if (!sub.isNullOrBlank()) addView(TextView(ctx).apply {
                text = sub; textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                setTextColor((fg and 0xFFFFFF) or 0xC0000000.toInt())
            })
            setOnClickListener { dialog.dismiss(); onClick() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(8) })
    }

    fun dismiss() = dialog.dismiss()

    fun show(onCancel: () -> Unit) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(body)
        dialog.setOnCancelListener { onCancel() }
        // ナビゲーションバーに重ならないよう、下の余白を足す
        ViewCompat.setOnApplyWindowInsetsListener(body) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, px(16) + b)
            insets
        }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(MATCH_PARENT, WRAP_CONTENT)
            setWindowAnimations(android.R.style.Animation_InputMethod) // 下からせり上がる
        }
        dialog.show()
    }
}
