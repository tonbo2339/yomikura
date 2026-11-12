package com.tonbo2339.yomikura

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * バーを隠している間、バーのある端に出す目印 (細い棒、または小さなボタン)。
 * 棒は「ここからスワイプ」の印、ボタンは押すとバーが出る。縦の位置は、棒もボタンも settings.barButtonPos (設定の「縦の位置」。ボタンは、なぞっても動かせる。HandleLayout)。
 * 見た目の設定は settings.barHidden / barButton*。いつ出すかは [shown] が決める (バーの状態は MainActivity と TopBar が持つ)。
 * [content] = 目印を重ねるページの箱 (ボタンの縦の位置の基準)。[onPress] = ボタンを押したとき。
 */
class BarHandle(
    private val ctx: Context,
    private val theme: BarTheme,
    private val cfg: AppSettings,
    private val content: FrameLayout,
    private val shown: () -> Boolean,
    private val onPress: () -> Unit,
) {
    private val dp = theme.dp
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val icon = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_more)
        imageTintList = ColorStateList.valueOf(theme.pal.text)
        visibility = View.GONE
    }

    /** マークだけのとき、マークの下に敷く縁取り (マークと逆の明るさの色で、少し大きく重ねる。背景や文字と同じ色でも見えるように) */
    private val halo = ImageView(ctx).apply { visibility = View.GONE }

    init {
        // ページの箱の高さが変わったら (回転・窓の大きさの変更・全画面の切り替え)、ボタンの縦の位置を置き直す (位置は高さに対する割合で覚えている)
        content.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot && cfg.barPos() != "top") update() } // 上のバーの棒の位置は箱の高さに依らない (バーの出し入れで高さが毎コマ変わるので、そのたびに作り直さない)
    }

    /** ページの箱に重ねる目印の部品 */
    val view = FrameLayout(ctx).apply {
        visibility = View.GONE
        addView(halo, FrameLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt(), Gravity.CENTER))
        addView(icon, FrameLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt(), Gravity.CENTER))
    }

    /** 目印の種類・置き場所・色・出し入れを、いまの設定とバーの状態に合わせる。 */
    fun update() {
        val pos = cfg.barPos()
        val kind = if (pos == "top") "line" else cfg.barHidden() // 上のバーは、棒だけ
        val button = kind == "button"
        val long = (HandleLayout.LINE_LENGTH_DP * dp).toInt()
        val thin = (5 * dp).toInt()
        val size = (HandleLayout.BUTTON_DP * dp).toInt()
        val w = if (button) size else if (pos == "top") long else thin
        val h = if (button) size else if (pos == "top") thin else long
        val side = if (pos == "right") Gravity.END else Gravity.START
        // 左右は、棒もボタンも、縦の位置を自由に決められる (topMargin で置く。位置は settings.barButtonPos。HandleLayout)
        val lp = FrameLayout.LayoutParams(w, h, if (pos == "top") Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.TOP or side)
        // 上のバーの棒は、OS の状態バーが触りを受ける所 (実機で、状態バーの下 約 18dp まで) より下に置く。
        // 左右は、画面の端にぴったり付けると、ケースや丸い縁で見えないので、少し内側に置く (スワイプを受ける範囲 28dp の内側)
        val gap = ((if (button) 6 else if (pos == "top") 26 else 9) * dp).toInt()
        when (pos) { "left" -> lp.marginStart = gap; "right" -> lp.marginEnd = gap; else -> lp.topMargin = gap }
        if (pos != "top") lp.topMargin = HandleLayout.top(cfg.barButtonPos(), content.height, h)
        view.layoutParams = lp
        val tint = if (button) buttonColor(cfg.barButtonColor()) else (theme.pal.subtext and 0xFFFFFF) or 0xB0000000.toInt()
        val plain = button && cfg.barButtonShape() == "plain" // マークだけ (背景の丸なし)
        view.background = if (plain) null else theme.pill(tint)
        if (button) {
            icon.setImageResource(buttonIconRes(cfg.barButtonIcon(), pos))
            // 丸があるときは、丸の色に白 (灰色だけは、ページの文字色)。マークだけのときは、選んだ色をマークそのものの色にする
            val gray = cfg.barButtonColor() == "gray"
            val markColor = when {
                gray -> theme.pal.text
                plain -> buttonColor(cfg.barButtonColor()) or 0xFF000000.toInt()
                else -> Color.WHITE
            }
            icon.imageTintList = ColorStateList.valueOf(markColor)
            icon.alpha = if (plain) cfg.barButtonAlpha() / 100f else 1f
            icon.layoutParams = (icon.layoutParams as FrameLayout.LayoutParams).also { val s = ((if (plain) 30 else 22) * dp).toInt(); it.width = s; it.height = s }
            halo.visibility = if (plain) View.VISIBLE else View.GONE
            if (plain) {
                halo.setImageResource(buttonIconRes(cfg.barButtonIcon(), pos))
                // マークが暗ければ白、明るければ黒の縁にする
                halo.imageTintList = ColorStateList.valueOf(if (androidx.core.graphics.ColorUtils.calculateLuminance(markColor) < 0.5) Color.WHITE else Color.BLACK)
                halo.alpha = cfg.barButtonAlpha() / 100f
                halo.layoutParams = (halo.layoutParams as FrameLayout.LayoutParams).also { val s = (38 * dp).toInt(); it.width = s; it.height = s }
            }
        } else halo.visibility = View.GONE
        icon.visibility = if (button) View.VISIBLE else View.GONE
        view.isClickable = button
        view.contentDescription = if (button) "タブバーを出す (ドラッグで位置を動かせます)" else null
        view.setOnTouchListener(if (button) touch(size) else null)
        view.visibility = if (shown()) View.VISIBLE else View.GONE
        // 高さが決まってから、位置を置き直す。高さが 0 のまま (とても小さい窓など) のときに、毎コマ呼び続けないよう、数回で止める
        if (pos != "top" && content.height == 0 && retries++ < 10) content.postDelayed({ update() }, 100) else if (content.height > 0) retries = 0
    }

    private var retries = 0

    /** 小さなボタンの色 (濃さは settings.barButtonAlpha)。gray = ページの文字色に近い灰色 */
    private fun buttonColor(key: String): Int {
        val rgb = when (key) {
            "green" -> 0x1E9A55
            "blue" -> 0x2F6FD6
            "orange" -> 0xE07B00
            "pink" -> 0xD6477A
            "custom" -> Color.HSVToColor(floatArrayOf(cfg.barButtonHue().toFloat(), cfg.barButtonSat() / 100f, cfg.barButtonVal() / 100f)) and 0xFFFFFF
            else -> theme.pal.subtext and 0xFFFFFF
        }
        val a = (255 * cfg.barButtonAlpha() / 100).coerceIn(0, 255)
        return (a shl 24) or rgb
    }

    /** 小さなボタンのマーク。arrow は、バーが出てくる向き (左のバーなら右向き) */
    private fun buttonIconRes(key: String, pos: String): Int = when (key) {
        "dot" -> R.drawable.ic_dot
        "settings" -> R.drawable.ic_settings
        "book" -> R.drawable.ic_book
        "arrow" -> if (pos == "right") R.drawable.ic_arrow_back else R.drawable.ic_arrow_forward
        else -> R.drawable.ic_more
    }

    /** 小さなボタンの触り方: 押して離すとバーを出す。縦に動かすと、ボタンがついてきて、離した位置を覚える。動かした触りは、押したことにしない。 */
    private fun touch(size: Int): View.OnTouchListener {
        var startY = 0f
        var startTop = 0
        var dragging = false
        var cancelled = false // 指が増えた・システムに触りを取られた: 押したことにも、動かしたことにもしない
        return View.OnTouchListener { v, ev ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = ev.rawY; startTop = lp.topMargin; dragging = false; cancelled = false }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancelled = true
                MotionEvent.ACTION_MOVE -> {
                    if (!cancelled) {
                        if (!dragging && Math.abs(ev.rawY - startY) > slop) dragging = true
                        if (dragging) {
                            lp.topMargin = (startTop + (ev.rawY - startY).toInt()).coerceIn(0, (content.height - size).coerceAtLeast(0))
                            v.layoutParams = lp
                        }
                    }
                }
                MotionEvent.ACTION_UP -> if (!cancelled) {
                    if (dragging) {
                        val room = (content.height - size).coerceAtLeast(1)
                        val frac = (lp.topMargin.toDouble() / room).coerceIn(0.0, 1.0)
                        NHData.update(ctx) { d -> NHData.settingsForWrite(d).put("barButtonPos", frac) }
                    } else {
                        onPress()
                    }
                }
            }
            true
        }
    }
}
