package com.tonbo2339.yomikura

import android.content.Context
import android.os.StatFs

/**
 * 端末の空き容量 (オフライン保存の置き場所 = アプリの内部領域) の見方と、「少ない」の目安。
 * - [LOW_BYTES]: 保存を始める前に、使う人に「保存しますか」と聞く目安 (画面は ui/ui-save.js)。更新の自動保存は、これより少ないと見送る。
 * - [MIN_BYTES]: 保存を始めない下限 (サービスが順番待ちを「止めました」にする)。これ以下では、書き込みが失敗して同じ作品を何度も取り直しうる。
 * 判断の部分 ([shouldAsk]) は Android に依存しない (単体テストで動かせる)。
 */
object StorageSpace {
    const val LOW_BYTES = 500L * 1024 * 1024
    const val MIN_BYTES = 50L * 1024 * 1024

    /** 空き容量 (バイト)。調べられなければ -1 */
    fun freeBytes(ctx: Context): Long = try {
        StatFs(ctx.filesDir.absolutePath).availableBytes
    } catch (e: Exception) {
        -1L
    }

    /** 保存の前に聞くか: 空きが [LOW_BYTES] より少ないか、保存の見込み ([estimate]) の 2 倍に足りないとき。空きが分からない (負) なら聞かない */
    fun shouldAsk(free: Long, estimate: Long): Boolean = free >= 0 && free < maxOf(LOW_BYTES, estimate.coerceAtLeast(0L) * 2)

    /** 保存を始めてよいか: 空きが [MIN_BYTES] 以上。空きが分からない (負) なら、始めてよい */
    fun canStart(free: Long): Boolean = free < 0 || free >= MIN_BYTES
}
