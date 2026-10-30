package com.tonbo2339.yomikura

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import android.webkit.WebView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 開発用ログ (Edition.DEBUG_TOOLS が true の版だけ。公開版は何も記録しない)。通信・エラーなど、あとから調べたい出来事を、時刻つきで 1 か所に残す。
 * 診断情報 ([Diag]) と違い、URL は全部 (クエリも) 残る。Cookie・ログイン情報の中身は残さない。外へは出ず、書き出したときだけ Download フォルダへ。
 *
 * - 入口は [event] だけ。分類 ([Category]) ごとに、設定 (デバッグのページ) でオン・オフできる。スイッチ `netLog` がオフなら何も残さない。
 * - 書き込みは専用の裏のスレッドで行う (呼ぶ側の処理を止めない)。filesDir/devlog.log に追記し、設定の大きさ (MB) を超えたら devlog.old に回す。
 *   起動のたびに、設定の日数より古い行を捨てる。プロセスの最初の 1 件の前に、版・端末などの 1 行 (`app start`) を入れる。
 * - ファイルの出し入れは [DevLogStore]、行の形と絞り込みの決まりは [DevLogFormat] (どちらも Android に依存しない)。
 *   前の版の通信の記録 (netlog.log) は、最初の起動で新しい形に直して取り込む。
 */
object DevLog {
    private const val TAG = "Yomikura.devlog"
    private const val DAY_MS = 86_400_000L

    /** 分類。key = 分類ごとのスイッチの設定名 (null = いつも残す)。足すときは、ここだけ (画面は一覧を Kotlin から受け取る) */
    enum class Category(val id: String, val label: String, val key: String?) {
        APP("app", "アプリ", null),
        NET("net", "通信", "devLogNet"),
        ERROR("error", "エラー・警告", "devLogError"),
        CHECK("check", "確認", "devLogCheck"), // 更新の確認: 始めた・見送った理由・終わった (アラーム・裏のジョブ・画面・起動時の判断)
        SCREEN("screen", "画面", "devLogScreen"), // 画面の復帰・消灯・メモリ不足・表示の様子 (真っ黒になる件の切り分け)
        SITE("site", "サイト", "devLogSite"), // サイトの休止・年齢確認の案内など
        OFFLINE("offline", "オフライン", "devLogOffline"), // オフライン保存のキューの動き
    }

    @Volatile private var app: Context? = null
    @Volatile private var settings: AppSettings? = null
    private var headerWritten = false // 書き込みのスレッドだけが触る

    private val io: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "Yomikura-devlog").apply { isDaemon = true } }
    }

    /** [Diag.init] から呼ぶ。この版で記録しないなら何もしない */
    @Synchronized
    fun init(context: Context) {
        if (!Edition.DEBUG_TOOLS || app != null) return
        val a = context.applicationContext
        app = a
        settings = AppSettings(a)
        io.execute {
            runCatching {
                DevLogStore.migrateLegacy(a.filesDir)
                DevLogStore.dropOlderThan(a.filesDir, System.currentTimeMillis() - (settings?.devLogDays() ?: 7) * DAY_MS)
            }
        }
    }

    /** 記録するか: この版で使えて、スイッチがオンで、(分類を渡したときは) その分類もオン */
    fun on(cat: Category? = null): Boolean {
        val s = settings ?: return false
        return s.devLogOn() && (cat?.key == null || s.devLogCategory(cat.key))
    }

    /** 出来事を 1 件残す。要約は短く、細かい値は fields (`DevLogFormat.fieldsOf`) へ */
    fun event(cat: Category, message: String, fields: String = "", level: Char = 'I') {
        if (!on(cat)) return
        val at = System.currentTimeMillis()
        val line = DevLogFormat.format(at, cat.id, level, message, fields)
        if (cat != Category.ERROR) Log.d(TAG, line) // エラー・警告は Diag が Logcat に出す
        io.execute { runCatching { append(at, line) } }
    }

    /** [event] の、項目を key=value の組で渡す形 (値が null の組は飛ばす) */
    fun eventOf(cat: Category, message: String, vararg fields: Pair<String, Any?>, level: Char = 'I') =
        event(cat, message, DevLogFormat.fieldsOf(*fields), level)

    // ---- 書き込み (裏のスレッド) ----

    private fun append(at: Long, line: String) {
        val dir = app?.filesDir ?: return
        val max = (settings?.devLogMaxMb() ?: 2) * 1_000_000L
        if (!headerWritten) { headerWritten = true; DevLogStore.append(dir, header(at), max) }
        DevLogStore.append(dir, line, max)
    }

    /** プロセスの最初に入れる 1 行: 版・端末・WebView。ログを書き出して読むとき、どの版・端末のものか分かるように。時刻は最初の 1 件と同じにする (並びが逆にならないように) */
    private fun header(at: Long): String {
        val ctx = app
        val pkg = ctx?.let { runCatching { it.packageManager.getPackageInfo(it.packageName, 0) }.getOrNull() }
        @Suppress("DEPRECATION")
        val code = pkg?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
        val web = runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()
        val fields = DevLogFormat.fieldsOf(
            "version" to pkg?.versionName, "code" to code, "build" to if (BuildConfig.DEBUG) "debug" else "release",
            "android" to "${Build.VERSION.RELEASE}/${Build.VERSION.SDK_INT}", "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "webview" to web, "pid" to Process.myPid(),
        )
        return DevLogFormat.format(at, Category.APP.id, 'I', "start", fields) + "\n" +
            DevLogFormat.format(at, Category.APP.id, 'I', "settings", settingsSummary())
    }

    /** 主な設定の値 (数・真偽・短い選択肢だけ。診断情報と同じ一覧)。起動の 1 行の次に入れて、「どの設定で起きたか」がログだけで分かるようにする */
    private fun settingsSummary(): String {
        val s = settings?.settings() ?: return ""
        return DiagReport.shownSettings(s).joinToString(" ")
    }

    /** アプリが落ちたとき、原因 (例外の型・メッセージ・先頭の数行) を、落ちる前に残す。もとの処理 (OS の終了) はそのまま続ける */
    fun recordCrash(thread: String, e: Throwable) {
        val at = System.currentTimeMillis()
        val stack = e.stackTrace.take(8).joinToString(" | ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        val line = DevLogFormat.format(at, Category.ERROR.id, 'E', "CRASH ${e.javaClass.name}", DevLogFormat.fieldsOf("thread" to thread, "message" to e.message, "at" to stack, "cause" to e.cause?.javaClass?.name))
        val dir = app?.filesDir ?: return
        if (!on(Category.ERROR)) return
        // 落ちる途中なので、裏のスレッドに任せず、この場で書く (書き終えるまで待つ)
        runCatching { DevLogStore.append(dir, line, (settings?.devLogMaxMb() ?: 2) * 1_000_000L) }
    }

    // ---- 読み出し (書き込みの列に並べるので、直前に書いた分も読める) ----

    private fun <T> onIo(fallback: T, block: () -> T): T =
        try { io.submit(Callable(block)).get(3, TimeUnit.SECONDS) } catch (e: Exception) { fallback }

    /** 古い順。categories が空なら全部。query は行のどこかに含まれる行だけ。limit > 0 なら新しい方の limit 行 */
    fun text(categories: Set<String>? = null, query: String = "", limit: Int = 0): String {
        val dir = app?.filesDir ?: return ""
        return onIo("") {
            val lines = DevLogFormat.filter(DevLogStore.lines(dir), categories, query)
            (if (limit > 0) lines.takeLast(limit) else lines).joinToString("\n")
        }
    }

    fun lineCount(): Int {
        val dir = app?.filesDir ?: return 0
        return onIo(0) { DevLogStore.lines(dir).size }
    }

    fun sizeBytes(): Long {
        val dir = app?.filesDir ?: return 0L
        return onIo(0L) { DevLogStore.sizeBytes(dir) }
    }

    fun clear() {
        val dir = app?.filesDir ?: return
        onIo(Unit) {
            DevLogStore.clear(dir)
            headerWritten = false // 消したあとの最初の 1 件の前にも、版などの行を入れる
        }
    }

    /** 「一色のまま残っていた表示」の画像を、Download フォルダへ書き出す。書き出した枚数 (保存した画像が無ければ 0) */
    fun exportShots(ctx: Context): Int {
        val shots = ShotStore.list(ctx.filesDir)
        var n = 0
        for (f in shots) {
            val ok = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "yomikura-" + f.name)
                    put(MediaStore.Downloads.MIME_TYPE, "image/png")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download")
                }
                val resolver = ctx.applicationContext.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri != null && resolver.openOutputStream(uri)?.use { it.write(f.readBytes()) } != null
            }.getOrDefault(false)
            if (ok) n++
        }
        return n
    }

    fun shotCount(ctx: Context): Int = ShotStore.list(ctx.filesDir).size

    fun clearShots(ctx: Context) = ShotStore.list(ctx.filesDir).forEach { it.delete() }

    /** Download フォルダへ `yomikura-devlog-<日時>.tsv` として書き出す (全部。絞り込みなし)。できたらファイル名、できなければ null */
    fun export(ctx: Context): String? {
        if (app == null) return null
        val body = text()
        if (body.isEmpty()) return null
        val name = "yomikura-devlog-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.JAPAN).format(Date())}.tsv"
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/tab-separated-values")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download")
            }
            val resolver = ctx.applicationContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) } ?: return null
            name
        }.getOrNull()
    }
}
