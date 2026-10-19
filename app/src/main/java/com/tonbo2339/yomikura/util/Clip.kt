package com.tonbo2339.yomikura

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** クリップボードへの書き込みの共通の入口。 */
object Clip {
    fun copy(ctx: Context, label: String, text: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
