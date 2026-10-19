package com.tonbo2339.yomikura

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** アプリを更新したあとの最初の起動で 1 回だけ出す、この版の変更点 (文と、出すかの判断は ReleaseNotes)。 */
object ReleaseNotesDialog {
    fun show(activity: AppCompatActivity, dp: Float, blocks: List<ReleaseNotes.Block>) {
        if (blocks.isEmpty() || activity.isFinishing || activity.isDestroyed) return
        val pad = (20 * dp).toInt()
        val text = TextView(activity).apply {
            textSize = 15f
            setLineSpacing(0f, 1.25f)
            setPadding(pad, (8 * dp).toInt(), pad, pad)
        }
        text.text = spanned(blocks, text.paint.measureText(BULLET).toInt())
        AlertDialog.Builder(activity)
            .setTitle("${BuildConfig.APP_NAME} ${BuildConfig.VERSION_NAME} の変更点")
            .setView(ScrollView(activity).apply { addView(text) })
            .setPositiveButton("閉じる", null)
            .show()
    }

    private const val BULLET = "・"

    /**
     * まとまりの並びを、見出しは太字・少し大きく、箇条書きは「・」付き (折り返した行は「・」のあとにそろえる) の文にする。
     * まとまりの間は 1 行あける。見出しのすぐ下と、続く箇条書きの間は半分だけ (設定の画面の見た目に近づける)
     */
    private fun spanned(blocks: List<ReleaseNotes.Block>, bulletWidth: Int): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        blocks.forEachIndexed { i, b ->
            if (i > 0) {
                val prev = blocks[i - 1].kind
                val half = prev == ReleaseNotes.Kind.HEADING || (prev == ReleaseNotes.Kind.BULLET && b.kind == ReleaseNotes.Kind.BULLET)
                sb.append("\n")
                val s = sb.length
                sb.append("\n")
                if (half) sb.setSpan(RelativeSizeSpan(0.5f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val start = sb.length
            if (b.kind == ReleaseNotes.Kind.BULLET) sb.append(BULLET)
            b.spans.forEach { (t, bold) ->
                val s = sb.length
                sb.append(t)
                if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (b.kind == ReleaseNotes.Kind.HEADING) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(1.1f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (b.kind == ReleaseNotes.Kind.BULLET) {
                sb.setSpan(LeadingMarginSpan.Standard(0, bulletWidth), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }
}
