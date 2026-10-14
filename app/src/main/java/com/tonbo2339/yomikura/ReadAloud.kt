package com.tonbo2339.yomikura

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 本文の読み上げ (Android の音声合成)。ページの JS が段落に分けた本文 (reader.js の nhTtsParagraphs) を受け取り、順に読む。
 * 読み始める段落ごとに、ページの JS (nhTtsMark) で色をつけて画面に出す。読み終わる・止める・エラーで onState(false)。
 *
 * - 日本語の声が無い端末では、始められない (理由を返す)。声は端末の設定 (音声合成の出力) のものを使う
 * - 画面を離れる・別のページへ移る・アプリを閉じるときは、呼ぶ側 (MainActivity) が stop / shutdown する
 * - run = ページの JS を実行する (画面のスレッドで)。onState(読んでいるか) は画面のスレッドで呼ぶ (post)
 */
class ReadAloud(
    private val ctx: Context,
    private val run: (String) -> Unit,
    private val post: (() -> Unit) -> Unit,
    private val onState: (Boolean) -> Unit,
    private val onProblem: (String) -> Unit,
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var unavailable: String? = null
    private var pending: Pair<List<TtsChunker.Chunk>, Float>? = null
    @Volatile private var chunks: List<TtsChunker.Chunk> = emptyList() // 音声合成の知らせ (別のスレッド) からも読む
    @Volatile private var gen = 0 // 読み始めるたびに増やす。止めた前の読み上げの終了通知を、今のものと取り違えない
    private val speakingFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    val speaking: Boolean get() = speakingFlag.get()

    /** 読み始める。始められないときは理由 (画面に出す文) を返す。rate = 速さ (1.0 が標準) */
    fun start(paragraphs: List<String>, rate: Float): String? {
        // 前に使えなかったときも、押されたらもう一度準備から試す (あとから日本語の声を入れた、など)。使えなければ onProblem で知らせる
        unavailable = null
        val list = TtsChunker.chunk(paragraphs)
        if (list.isEmpty()) return "読み上げられる本文がありません"
        stop()
        if (tts == null) {
            pending = list to rate
            tts = TextToSpeech(ctx.applicationContext) { status -> post { onInit(status) } }
        } else if (ready) {
            speak(list, rate)
        } else {
            pending = list to rate // 準備の途中。準備ができたら読み始める
        }
        return null
    }

    private fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) { fail("音声合成を使えませんでした (Android の設定の「音声合成の出力」を確かめてください)"); return }
        val r = t.setLanguage(Locale.JAPANESE)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            fail("日本語の音声データがありません (Android の設定の「音声合成の出力」で、日本語の声を入れてください)"); return
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val i = indexOf(utteranceId) ?: return
                val c = chunks.getOrNull(i) ?: return
                // 同じ段落の続きなら、色を付け直さない
                if (i == 0 || chunks.getOrNull(i - 1)?.para != c.para) post { run("window.nhTtsMark && nhTtsMark(${c.para})") }
            }
            override fun onDone(utteranceId: String?) {
                if (indexOf(utteranceId) == chunks.size - 1) finish()
            }
            override fun onError(utteranceId: String?) { if (indexOf(utteranceId) != null) finish() }
        })
        ready = true
        pending?.let { (list, rate) -> pending = null; speak(list, rate) }
    }

    private fun speak(list: List<TtsChunker.Chunk>, rate: Float) {
        val t = tts ?: return
        chunks = list
        gen++
        t.setSpeechRate(rate.coerceIn(0.5f, 3.0f))
        speakingFlag.set(true)
        post { onState(true) }
        list.forEachIndexed { i, c -> t.speak(c.text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "g$gen-c$i") }
    }

    /** 今の読み上げの発話 id から段の番号を取り出す。前の読み上げのものなら null */
    private fun indexOf(utteranceId: String?): Int? {
        val id = utteranceId ?: return null
        if (!id.startsWith("g$gen-c")) return null
        return id.removePrefix("g$gen-c").toIntOrNull()
    }

    private fun fail(message: String) {
        unavailable = message
        pending = null
        tts?.shutdown(); tts = null; ready = false
        post { onProblem(message) }
        finish()
    }

    /** 読み終わった・止めた・エラー: 色を消して、読んでいない状態に */
    private fun finish() {
        if (!speakingFlag.compareAndSet(true, false)) return // 読み終わりとエラーが重なっても、1 回だけ
        post { run("window.nhTtsMark && nhTtsMark(-1)"); onState(false) }
    }

    fun stop() {
        pending = null
        tts?.stop()
        finish()
    }

    /** アプリを閉じるとき */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    /** 準備できていない・声が無いなど、始められない理由 (無ければ null)。メニューを出す前の確認に使える */
    val problem: String? get() = unavailable
}
