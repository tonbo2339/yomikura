package com.tonbo2339.yomikura

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 設定の「データの管理」のバックアップを、ファイルに書き出す・ファイルから読み込む (Android の保存先・ファイルを選ぶ画面を使う)。
 * 中身を作る・読み込んだ中身を反映するのは画面の JS (書き出す中身は startExport に渡され、読み込んだ文は nhImport へ返す)。結果は nhBackupResult。
 *
 * 書き出しは保存先の画面を挟むので、書く中身を控えておく。保存先の画面を開いている間にアプリのプロセスが終わることがあるので、
 * 同じ中身をキャッシュのファイルにも控え、作り直されたあとはそちらから戻す。
 * Activity を作るとき (onCreate より前。registerForActivityResult のため) に作り、onCreate で [restore]、onSaveInstanceState で [save] を呼ぶ。
 */
class BackupFiles(
    private val activity: AppCompatActivity,
    private val evalJs: (String) -> Unit,
    private val emitJs: (String, JSONObject) -> Unit,
) {
    /** 書き出す (ファイル名, 中身)。保存先を選び終えるまで持つ */
    private var pending: List<Pair<String, String>> = emptyList()
    private val pendingFile by lazy { File(activity.cacheDir, PENDING_FILE) }
    private val resolver get() = activity.contentResolver

    /** 書き出しの控え: 保存先の画面を開いたままプロセスが終わって作り直されたとき (saved に印がある) だけ残す。それ以外の古い控えは消す */
    fun restore(saved: Bundle?) {
        if (saved?.getString(KEY_PENDING) != PENDING_FILE) pendingFile.delete()
    }

    fun save(out: Bundle) {
        if (pending.isNotEmpty() || pendingFile.isFile) out.putString(KEY_PENDING, PENDING_FILE)
    }

    /** mode = "many" ならフォルダを選んで複数のファイル、それ以外は 1 つのファイル */
    fun startExport(mode: String, files: List<Pair<String, String>>) {
        pending = files
        stash(files)
        try {
            if (mode == "many") exportMany.launch(null) else exportOne.launch(files.first().first)
        } catch (e: Exception) {
            take()
            result(false, "保存先の画面を開けませんでした")
        }
    }

    fun startImport() {
        try { importDocs.launch(arrayOf("*/*")) } catch (e: Exception) { result(false, "ファイルの選択画面を開けませんでした") }
    }

    /** 控えた書き出しの中身を取り出して、控えを消す。プロセスが終わって失われていたら null */
    private fun take(): List<Pair<String, String>>? {
        val inMemory = pending
        pending = emptyList()
        val file = pendingFile
        try {
            if (inMemory.isNotEmpty()) return inMemory
            if (!file.isFile) return null
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            return List(arr.length()) { arr.getJSONObject(it).let { o -> o.getString("name") to o.getString("text") } }.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            return null
        } finally {
            file.delete()
        }
    }

    private fun stash(files: List<Pair<String, String>>) {
        Thread {
            try {
                val arr = JSONArray()
                for ((name, text) in files) arr.put(JSONObject().put("name", name).put("text", text))
                pendingFile.writeText(arr.toString(), Charsets.UTF_8)
            } catch (e: Exception) {
                Diag.w(TAG, "pending export not saved", e)
            }
        }.start()
    }

    private val exportOne = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val files = take()
        if (uri == null) return@registerForActivityResult result(false, "キャンセルしました")
        val f = files?.firstOrNull()
        if (f == null) {
            // アプリが作り直されて、書き出す中身が失われた: 保存先の画面が作った空のファイルは消す
            try { DocumentsContract.deleteDocument(resolver, uri) } catch (e: Exception) { /* 消せなければそのまま */ }
            return@registerForActivityResult result(false, LOST)
        }
        Thread {
            val ok = writeText(uri, f.second)
            result(ok, if (ok) "書き出しました" else "書き出せませんでした")
        }.start()
    }

    private val exportMany = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val files = take()
        if (tree == null) return@registerForActivityResult result(false, "キャンセルしました")
        if (files == null) return@registerForActivityResult result(false, LOST)
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        Thread {
            var n = 0
            for ((name, text) in files) {
                val uri = try { DocumentsContract.createDocument(resolver, dir, "application/json", name) } catch (e: Exception) { null }
                if (uri != null && writeText(uri, text)) n++
            }
            result(n == files.size && n > 0, if (n == files.size) "$n 個のファイルを書き出しました" else "$n / ${files.size} 個だけ書き出せました")
        }.start()
    }

    private val importDocs = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult result(false, "キャンセルしました")
        Thread {
            val texts = JSONArray()
            for (u in uris) {
                val t = readText(u) ?: return@Thread result(false, "読み込めないファイルがありました")
                texts.put(t)
            }
            evalJs("window.nhImport && nhImport(${JSONObject.quote(texts.toString())})")
        }.start()
    }

    private fun writeText(uri: Uri, text: String): Boolean = try {
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
    } catch (e: Exception) { false }

    /** 大きすぎるファイル ([MAX_BYTES] 超) は読まない */
    private fun readText(uri: Uri): String? = try {
        resolver.openInputStream(uri)?.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(65536)
            var total = 0
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) return@use null
                out.write(buf, 0, n)
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    } catch (e: Exception) { null }

    /** 結果を画面 (JS) に知らせる。裏のスレッドからも呼べる */
    private fun result(ok: Boolean, message: String) {
        emitJs("nhBackupResult", JSONObject().put("ok", ok).put("message", message))
    }

    companion object {
        private const val TAG = "Yomikura"
        private const val PENDING_FILE = "pending-export.json"
        private const val KEY_PENDING = "nh:pendingExport"
        private const val LOST = "書き出す前にアプリが終了したため、書き出せませんでした。もう一度書き出してください"
        private const val MAX_BYTES = 20 * 1024 * 1024
    }
}
