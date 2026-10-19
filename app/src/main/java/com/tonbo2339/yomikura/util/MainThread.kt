package com.tonbo2339.yomikura

import android.os.Handler
import android.os.Looper

/** 画面 (主スレッド) へ処理を回す共通の入口。各所で Handler を作らず、これを使う。 */
object MainThread {
    val handler: Handler by lazy { Handler(Looper.getMainLooper()) }

    fun isCurrent(): Boolean = Looper.myLooper() === Looper.getMainLooper()

    /** 主スレッドなら今すぐ、そうでなければ主スレッドへ回して実行する。 */
    fun run(r: Runnable) { if (isCurrent()) r.run() else handler.post(r) }
}
