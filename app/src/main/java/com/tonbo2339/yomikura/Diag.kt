package com.tonbo2339.yomikura

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Log.w / Log.e の代わり。Logcat に出すのは同じで、直近の分を [DiagBuffer] に残す (診断情報に載せる)。
 * [init] のあとは、アプリを閉じても残る (filesDir/diag.log。60 件まで)。init の前の分はメモリだけ。
 * 画面 (MainActivity)・裏のジョブ (BgCheckJob)・保存のサービス (OfflineService) が、始まるときに init を呼ぶ。
 */
object Diag {
    private val buf = DiagBuffer()
    @Volatile private var file: File? = null

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "diag.log")
        file = f
        runCatching { if (f.isFile) buf.load(f.readText()) }
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (tr == null) Log.w(tag, msg) else Log.w(tag, msg, tr)
        record('W', tag, msg, tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr == null) Log.e(tag, msg) else Log.e(tag, msg, tr)
        record('E', tag, msg, tr)
    }

    @Synchronized
    private fun record(level: Char, tag: String, msg: String, tr: Throwable?) {
        val text = if (tr == null) msg else "$msg (${tr.javaClass.simpleName}: ${tr.message ?: ""})"
        buf.add(System.currentTimeMillis(), level, tag, text)
        file?.let { f -> runCatching { f.writeText(buf.toText()) } }
    }

    /** 古い順 */
    fun entries(): List<DiagBuffer.Entry> = buf.entries()

    fun clear() {
        buf.clear()
        file?.let { runCatching { it.delete() } }
    }
}
