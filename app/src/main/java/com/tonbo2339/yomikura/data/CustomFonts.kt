package com.tonbo2339.yomikura

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/**
 * 自分で入れたフォント (設定の「本文の文字」)。アプリの中のフォルダ [dir] に、ファイル `<id>.<拡張子>` と一覧 `index.json` ([{id, name, ext}]) を置く。
 * 一覧と名前の持ち主はここ (設定の font には `custom:<id>` だけを置く)。Android には依存しない。
 * name = 一覧に出す名前 (初めはフォントの中の名前。利用者が変えられる)、family = フォントの中の英語の名前 (内部用。変えない。読めなければ空)。
 * 読む・追加・削除は、別のスレッドから同時に来ても壊れないよう [lock] で守る。
 */
class CustomFonts(private val dir: File) {
    class Font(val id: String, val name: String, val ext: String, val family: String = "")

    /** 追加の結果。ok でなければ message を利用者に見せる */
    class Added(val ok: Boolean, val message: String, val font: Font? = null)

    private val lock = Any()
    private val index get() = File(dir, INDEX)

    fun list(): List<Font> = synchronized(lock) { read() }

    fun toJson(): String = JSONArray().also { a -> list().forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) } }.toString()

    /** id のフォントのファイル。id の形が違う・無いときは null (id は URL から来るので、形を確かめてから使う) */
    fun file(id: String): File? = synchronized(lock) {
        if (!ID.matches(id)) return null
        read().firstOrNull { it.id == id }?.let { File(dir, "${it.id}.${it.ext}") }?.takeIf { it.isFile }
    }

    /** 配信するときの MIME (拡張子から) */
    fun mimeOf(id: String): String? = synchronized(lock) {
        read().firstOrNull { it.id == id }?.let { if (it.ext == "otf") "font/otf" else if (it.ext == "ttc") "font/collection" else "font/ttf" }
    }

    /**
     * 選んだファイルの中身 (input) を、名前 displayName で追加する。大きすぎる ([MAX_BYTES] 超)・フォントでない・数が多すぎる ([MAX_FONTS]) ときは断る。
     * 中身は、見出しの数バイトで形を確かめる (拡張子では決めない)
     */
    fun add(displayName: String, input: InputStream): Added {
        synchronized(lock) {
            if (read().size >= MAX_FONTS) return Added(false, "追加できるのは $MAX_FONTS 個までです")
            dir.mkdirs()
            sweep()
        }
        val tmp = File(dir, TMP + randomId())
        val head: ByteArray
        try {
            var total = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(65536)
                while (total <= MAX_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total <= MAX_BYTES) out.write(buf, 0, n)
                }
            }
            // 書いているあいだはファイルを開いたままなので、消すのは閉じたあと
            if (total > MAX_BYTES) return fail(tmp, "ファイルが大きすぎます (${MAX_BYTES / 1024 / 1024} MB まで)")
            head = tmp.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        } catch (e: java.io.IOException) {
            return fail(tmp, "ファイルを読み込めませんでした")
        }
        val ext = extOf(head) ?: return fail(tmp, "フォントのファイルではありません (ttf・otf・ttc に対応しています)")
        val names = FontNames.read(tmp)
        return synchronized(lock) {
            val list = read()
            if (list.size >= MAX_FONTS) return@synchronized fail(tmp, "追加できるのは $MAX_FONTS 個までです")
            val id = randomId()
            val font = Font(id, initialName(names, displayName), ext, names?.english.orEmpty())
            val dest = File(dir, "$id.$ext")
            if (!tmp.renameTo(dest)) return@synchronized fail(tmp, "保存できませんでした")
            // 一覧に書けなかった (空き容量など) ときは、一覧に無いファイルを残さない
            try { write(list + font) } catch (e: java.io.IOException) { dest.delete(); return@synchronized Added(false, "保存できませんでした") }
            Added(true, "「${font.name}」を追加しました", font)
        }
    }

    /**
     * 掃除 (lock の中で): 古い書きかけ ([STALE_MS] より前。いま別の追加が写している最中のものは残す) と、一覧に無いフォントのファイル。
     * 一覧が壊れていて読めないときは、フォントのファイルを消さない (一覧だけ作り直せば戻せるように)
     */
    private fun sweep() {
        val listed = readOrNull()?.map { "${it.id}.${it.ext}" }?.toSet()
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { f ->
            when {
                f.name.startsWith(TMP) -> if (now - f.lastModified() > STALE_MS) f.delete()
                f.name == INDEX + NEW -> f.delete()
                listed != null && FONT_FILE.matches(f.name) && f.name !in listed -> f.delete()
            }
        }
    }

    /** 一覧に出す名前を変える。整えて空になるとき・id が無い・書けないときは false。英語の名前 (family) は変えない */
    fun rename(id: String, name: String): Boolean = synchronized(lock) {
        val list = read()
        val f = list.firstOrNull { it.id == id } ?: return false
        val clean = tidy(name).ifEmpty { return false }
        try { write(list.map { if (it === f) Font(f.id, clean, f.ext, f.family) else it }) } catch (e: java.io.IOException) { return false }
        true
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val list = read()
        val f = list.firstOrNull { it.id == id } ?: return false
        // 先に一覧から外す (書けなければ何も消さない)。ファイルが残っても、次の追加のときの掃除で消える
        try { write(list - f) } catch (e: java.io.IOException) { return false }
        File(dir, "${f.id}.${f.ext}").delete()
        true
    }

    /** すべて消す (全データの削除) */
    fun clear() = synchronized(lock) { dir.deleteRecursively(); Unit }

    private fun fail(tmp: File, message: String): Added { tmp.delete(); return Added(false, message) }

    /** 一覧。まだ無ければ空、壊れていて読めなければ null */
    private fun readOrNull(): List<Font>? {
        if (!index.isFile) return emptyList()
        return try {
            val a = JSONArray(index.readText(Charsets.UTF_8))
            List(a.length()) { a.getJSONObject(it) }.mapNotNull { o ->
                val id = o.optString("id")
                val ext = o.optString("ext", "ttf")
                if (ID.matches(id) && ext in EXTS) Font(id, o.optString("name"), ext, o.optString("family")) else null // ext はファイルの名前に使うので、決まった物だけ
            }
        } catch (e: Exception) { null }
    }

    private fun read(): List<Font> = readOrNull() ?: emptyList()

    /** 一時ファイルに書いてから置き換える (書く途中で止まっても、いまの一覧が壊れない) */
    private fun write(list: List<Font>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("ext", it.ext).put("family", it.family)) }
        val tmp = File(dir, INDEX + NEW)
        tmp.writeText(a.toString(), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val MAX_BYTES = 40L * 1024 * 1024
        const val MAX_FONTS = 10
        const val MAX_NAME = 40
        const val INDEX = "index.json"
        private const val TMP = "tmp-"
        private const val NEW = ".new"
        /** 写している途中のファイルを、書きかけとして掃除するまでの時間 */
        const val STALE_MS = 60L * 60 * 1000
        private val EXTS = setOf("ttf", "otf", "ttc")
        /** id の形 (JS の site/fonts.js と同じ) */
        val ID = Regex("^[0-9a-f]{12}$")
        private val FONT_FILE = Regex("^[0-9a-f]{12}\\.(ttf|otf|ttc)$")

        private val random = SecureRandom()
        private fun randomId(): String = ByteArray(6).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

        /** ファイルの先頭 4 バイトから、フォントの種類 (拡張子)。TrueType (00 01 00 00・true) / OpenType (OTTO) / コレクション (ttcf)。違えば null */
        fun extOf(head: ByteArray): String? {
            if (head.size < 4) return null
            val s = String(head, Charsets.ISO_8859_1)
            return when {
                head[0].toInt() == 0 && head[1].toInt() == 1 && head[2].toInt() == 0 && head[3].toInt() == 0 -> "ttf"
                s == "true" -> "ttf"
                s == "OTTO" -> "otf"
                s == "ttcf" -> "ttc"
                else -> null
            }
        }

        /** 選んだファイルの名前 (「Foo.ttf」) から、一覧に出す名前。拡張子を除き、長すぎれば切る。空なら「フォント」 */
        fun cleanName(displayName: String): String = tidy(displayName.substringBeforeLast('.', displayName)).ifEmpty { "フォント" }

        /** 初めの名前: フォントの中の日本語の名前 → 英語の名前 → ファイル名 (拡張子を除く) → 「フォント」 */
        fun initialName(names: FontNames.Names?, displayName: String): String =
            tidy(names?.local.orEmpty()).ifEmpty { tidy(names?.english.orEmpty()) }.ifEmpty { cleanName(displayName) }

        /** 表示する名前に整える: 制御文字・書式文字 (向きを変える文字など)・壊れた文字 (U+FFFD・対にならない上位下位) を除き、前後の空白を取り、文字 (コードポイント) で MAX_NAME まで */
        fun tidy(s: String): String {
            val sb = StringBuilder()
            var n = 0
            s.codePoints().forEach { cp ->
                val bad = cp == 0xFFFD || Character.getType(cp) in BAD_TYPES
                if (!bad && n < MAX_NAME * 4) { sb.appendCodePoint(cp); n++ }
            }
            val t = sb.toString().trim()
            return if (t.codePointCount(0, t.length) <= MAX_NAME) t else t.substring(0, t.offsetByCodePoints(0, MAX_NAME)).trim()
        }

        private val BAD_TYPES = setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt(), Character.UNASSIGNED.toInt(), Character.PRIVATE_USE.toInt())
    }
}
