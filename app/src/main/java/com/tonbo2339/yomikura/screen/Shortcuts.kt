package com.tonbo2339.yomikura

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * ランチャーのアイコンを長押ししたときに出るショートカット: 「続きから読む」(最後に読んだ作品の続き。Resume) と「リストを開く」。
 * アプリを開くたびに出し直す (版・言語が変わっても、古い物が残らない)。ショートカットに対応しない端末では何も起きない。
 */
object Shortcuts {
    const val ACTION_RESUME = "com.tonbo2339.yomikura.RESUME"

    fun publish(ctx: Context) {
        val app = ctx.applicationContext
        fun shortcut(id: String, label: String, icon: Int, action: String, rank: Int) = ShortcutInfoCompat.Builder(app, id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(app, icon))
            .setIntent(Intent(app, MainActivity::class.java).setAction(action))
            .setRank(rank)
            .build()
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(
                app,
                listOf(
                    shortcut("resume", "続きから読む", R.drawable.ic_shortcut_resume, ACTION_RESUME, 0),
                    shortcut("list", "リストを開く", R.drawable.ic_shortcut_list, BgCheck.ACTION_OPEN_LIST, 1),
                ),
            )
        }
    }
}
