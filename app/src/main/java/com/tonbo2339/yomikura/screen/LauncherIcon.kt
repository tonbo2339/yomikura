package com.tonbo2339.yomikura

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * ランチャーアイコンの切り替え (マニフェストの activity-alias のうち 1 つだけを有効にする)。
 *
 * - 今すぐ: [set] (restartNow = true)。ランチャーに反映するため、アプリを終了する (開き直しはしない)
 * - 後で: [queue]。保存データの settings.launcherIconPending に覚えておき、次にアプリを起動したとき [applyPending] が適用する
 * 有効な別名 (現在のアイコン) は、アプリの更新でも残る (ランチャーの設定はパッケージごとに保たれるため。別名を変えない限り消えない)。
 */
class LauncherIcon(private val ctx: Context) {

    // 別名のクラス名は、マニフェストの名前空間 (MainActivity と同じパッケージ) から作る。debug 版はパッケージ名に .debug が付くので、アプリの packageName は名前空間に使えない。
    // 注意: この LauncherIcon 自身のパッケージは使わない。リリース版は R8 でクラスのパッケージ名が変わる (s0 など) ため、存在しない別名を探して例外になる。
    // MainActivity はマニフェストが指すので、R8 でも名前が変わらない。
    private fun component(key: String) = ComponentName(ctx.packageName, "$ALIAS_PACKAGE.${ICONS.getValue(key)}")

    private fun isEnabled(key: String): Boolean =
        try {
            ctx.packageManager.getComponentEnabledSetting(component(key)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } catch (e: Exception) {
            Log.e(TAG, "別名の状態を読めません: $key", e)
            false
        }

    /** 有効になっているアイコンの別名 (activity-alias) を探す。どれも未変更ならマニフェストの既定 (paper)。 */
    fun current(): String = ICONS.keys.firstOrNull { isEnabled(it) } ?: DEFAULT_ICON

    /** 有効な別名のアイコンの絵 (通知の大アイコン用。マニフェストの icon から取るので、ここに色の表は持たない)。 */
    fun currentDrawable(): android.graphics.drawable.Drawable =
        ctx.packageManager.getActivityIcon(component(current()))

    /**
     * 選んだアイコンの別名だけを有効にする。restartNow = true なら、ランチャーに反映するためアプリを終了する (今すぐ)。
     * false なら、アプリを止めずに切り替える (次に起動したときの適用に使う)。
     * 失敗しても例外は外へ出さず、false を返す (起動時の適用で落ちて、アプリが起動できなくならないように)。
     */
    fun set(key: String, restartNow: Boolean): Boolean {
        if (key !in ICONS) return false
        if (key == current()) return true
        try {
            // 変更ごとにアプリを止めない (DONT_KILL_APP)。止めると、最初の変更でアプリが終わって、残りの別名を無効にする処理が途切れる
            // (別名が複数有効のままになり、ランチャーにアイコンが何個も出る)。止めるのは、全部の変更が終わったあと
            val flags = PackageManager.DONT_KILL_APP
            // 先に新しい方を有効にしてから古い方を消す (一瞬でもランチャーの入口が無くならないように)
            ctx.packageManager.setComponentEnabledSetting(component(key), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, flags)
            ICONS.keys.filter { it != key }.forEach {
                ctx.packageManager.setComponentEnabledSetting(component(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, flags)
            }
        } catch (e: Exception) {
            Log.e(TAG, "アイコンを切り替えられません: $key", e)
            return false
        }
        // 今すぐ: ランチャーに反映するため、アプリを終了する
        // 止める前に、保存データの書きかけ (apply) を書き終える (直前に変えた設定が消えないように)
        if (restartNow) {
            // 前に「後で」で選んだアイコンがあれば忘れる (次の起動で、いま選んだアイコンを古い選択に戻さない)
            try { NHData.update(ctx) { d -> NHData.settingsForWrite(d).remove(PENDING) } } catch (e: Exception) { Log.e(TAG, "覚えたアイコンを消せません", e) }
            NHData.flush(ctx)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        return true
    }

    /** 「後で」: 次にアプリを起動したとき適用する (今のアイコンは変えない)。 */
    fun queue(key: String) {
        if (key !in ICONS) return
        NHData.update(ctx) { d -> NHData.settingsForWrite(d).put(PENDING, key) }
    }

    /**
     * アプリの起動時: 「後で」で覚えたアイコンがあれば、アプリを止めずに適用する。
     * 覚えは先に消す。適用に失敗しても、次の起動で同じ失敗を繰り返して、アプリが起動できなくならないように。
     */
    fun applyPending() {
        val key = try {
            NHData.settings(ctx).optString(PENDING)
        } catch (e: Exception) {
            Log.e(TAG, "覚えたアイコンを読めません", e)
            return
        }
        if (key.isEmpty()) return
        try {
            NHData.update(ctx) { d -> NHData.settingsForWrite(d).remove(PENDING) }
        } catch (e: Exception) {
            Log.e(TAG, "覚えたアイコンを消せません", e)
            return // 消せないまま適用すると、失敗したときに毎回繰り返すので、ここでやめる
        }
        set(key, restartNow = false)
    }

    companion object {
        /** キー → マニフェストの activity-alias 名 (assets/web/icons/ にプレビューの SVG) */
        // 並びは設定画面の並び (state.js の LAUNCHER) と同じ。別名とキーは、変えると選択が消えるので変えない
        private val ICONS = linkedMapOf(
            "paper" to "IconPaper", "green" to "IconGreen", "ocean" to "IconOcean", "purple" to "IconPurple",
            "sakura" to "IconSakura", "aigold" to "IconAiGold", "yuyake" to "IconYuyake", "matcha" to "IconMatcha",
            "night" to "IconNight", "dusk" to "IconDusk", "wood" to "IconWood", "sea" to "IconSea",
        )
        /** 既定のアイコン。マニフェストで enabled = true の別名 (IconPaper) と同じにする */
        private const val DEFAULT_ICON = "paper"
        private const val TAG = "Yomikura.icon"
        /** 別名 (activity-alias) のクラス名の前半。マニフェストの名前空間で、MainActivity と同じパッケージ */
        private val ALIAS_PACKAGE = MainActivity::class.java.name.substringBeforeLast('.')
        /** 保存データの settings にある、「後で」で覚えたアイコン */
        const val PENDING = "launcherIconPending"
    }
}
