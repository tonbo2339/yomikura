package com.tonbo2339.yomikura

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 上部バーの下の、保存の進みの帯 (header の中。バーと一緒に隠れる。タップで保存キューの画面)。
 * 保存待ち・保存中・Wi-Fi 待ち・一時停止・確認が必要のどれかがあるときだけ出す。
 * 色: 保存中 = 緑、待ち・一時停止 = 橙、確認が必要 = 赤。保存キューの画面では出さない (同じ内容が画面にあるため)。
 */
class SaveStrip(ctx: Context, private val theme: BarTheme, private val queue: SaveQueue, onClick: () -> Unit) {
    private val dp = theme.dp
    private val text = TextView(ctx).apply {
        textSize = 13f
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
    }
    private val line = View(ctx).apply { pivotX = 0f }

    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setOnClickListener { onClick() }
        contentDescription = "保存キューを開く"
        background = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run { getDrawable(0).also { recycle() } }
        addView(text, LinearLayout.LayoutParams(MATCH_PARENT, (30 * dp).toInt()))
        addView(line, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(2, (2 * dp).toInt())))
    }

    /** キューとサービスの様子に合わせて直す。onQueuePage = 保存キューの画面 (出さない) */
    fun refresh(onQueuePage: Boolean) {
        val jobs = queue.all()
        val live = OfflineStatus.live
        val active = jobs.count { it.status.active }
        val needsCheck = jobs.any { it.status == SaveQueue.Status.NEEDS_CHECK }
        if (onQueuePage || (active == 0 && !needsCheck)) { view.visibility = View.GONE; return }
        val more = if (active > 1) " ・ 他 ${active - 1} 作品" else ""
        // 題名は長いことがあるので最後に置く (1 行に収まらなければ題名の末尾だけが「…」になり、件数は必ず見える)
        val progress = live?.let { " ${it.ok} / ${it.total} 話$more ・ ${it.title}" }.orEmpty()
        val wifiJobs = jobs.count { it.status == SaveQueue.Status.WAIT_WIFI }
        // 状態の言葉 (色付きの太字) と、そのあとの説明
        val (head, rest, color) = when {
            live != null && live.paused -> Triple("一時停止中", progress, BarTheme.SAVE_WAIT)
            live != null && live.waitingWifi -> Triple("Wi-Fi 待ち", progress, BarTheme.SAVE_WAIT)
            live != null -> Triple("保存中", progress, BarTheme.ACCENT)
            wifiJobs > 0 -> Triple("Wi-Fi 待ち", " $wifiJobs 作品", BarTheme.SAVE_WAIT)
            active > 0 -> Triple("順番待ち", " $active 作品", BarTheme.ACCENT)
            else -> Triple("確認が必要", " タップして保存キューを開く", BarTheme.SAVE_ALERT)
        }
        text.text = SpannableString(head + rest).apply {
            setSpan(ForegroundColorSpan(color), 0, head.length, 0)
            setSpan(StyleSpan(Typeface.BOLD), 0, head.length, 0)
        }
        text.setTextColor(theme.pal.subtext)
        line.setBackgroundColor(color)
        line.scaleX = if (live != null && live.total > 0) maxOf(live.ok.toFloat() / live.total, 0.02f) else 1f
        view.visibility = View.VISIBLE
    }
}
