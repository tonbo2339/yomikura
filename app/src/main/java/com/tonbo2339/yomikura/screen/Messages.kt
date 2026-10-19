package com.tonbo2339.yomikura

import android.content.Context
import android.widget.Toast

/**
 * アプリ内のお知らせの共通の入口 (画面の下のバー [Snack])。文・ボタン・画面が閉じても残すかだけを渡す。
 * どのスレッドからでも呼べる (画面の処理は主スレッドへ回す)。
 * 画面 (Snack) が無いとき・画面が閉じても残したいときは、Android のトースト (ボタンは付かない)。
 * ページの中のお知らせ (JS の NH.toast) は、ページの上に出す別のもの。
 */
object Messages {
    /** 画面の下のバー。MainActivity が作った間だけある。 */
    @Volatile private var snack: Snack? = null

    fun attach(s: Snack) { snack = s }

    /** 閉じる画面が自分のバーのときだけ外す (新しい画面のバーを消さない)。 */
    fun detach(s: Snack) { if (snack === s) snack = null }

    /**
     * お知らせを出す。button = 「元に戻す」などの押せるボタン (バーのときだけ付く)。
     * outlives = true なら、画面を閉じる前後でも残るトーストで出す。
     */
    fun show(ctx: Context, text: String, button: Pair<String, () -> Unit>? = null, outlives: Boolean = false) {
        val app = ctx.applicationContext
        val run = Runnable {
            val bar = snack
            if (bar != null && !outlives) bar.show(text, button)
            else Toast.makeText(app, text, if (text.length < 30) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
        MainThread.run(run)
    }
}
