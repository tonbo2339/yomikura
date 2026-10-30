package com.tonbo2339.yomikura

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * 設定の「フォントを追加」: 端末のファイルを選ぶ画面を出し、選んだファイルを [CustomFonts] に追加する。結果は画面 (JS) の nhFontResult へ ({ok, message, id})。
 * Activity を作るとき (onCreate より前。registerForActivityResult のため) に作る。
 */
class FontFiles(
    private val activity: AppCompatActivity,
    private val fonts: () -> CustomFonts, // filesDir は Activity を作った後でないと使えないので、使うときに取る
    private val emitJs: (String, JSONObject) -> Unit,
) {
    fun startPick() {
        try { pick.launch(arrayOf("*/*")) } catch (e: Exception) { result(false, "ファイルの選択画面を開けませんでした") }
    }

    private val pick = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult // やめたときは何も知らせない
        // 大きなファイルを写すので、裏のスレッドで
        runBackground("Yomikura-font") {
            val added = try {
                val input = activity.contentResolver.openInputStream(uri)
                if (input == null) CustomFonts.Added(false, "ファイルを読み込めませんでした") else input.use { fonts().add(displayName(uri), it) }
            } catch (e: Exception) {
                Diag.w(TAG, "font add failed", e)
                CustomFonts.Added(false, "ファイルを読み込めませんでした")
            }
            result(added.ok, added.message, added.font?.id)
        }
    }

    private fun displayName(uri: Uri): String = try {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: ""
    } catch (e: Exception) { "" }

    private fun result(ok: Boolean, message: String, id: String? = null) {
        emitJs("nhFontResult", JSONObject().put("ok", ok).put("message", message).put("id", id ?: ""))
    }

    private companion object { const val TAG = "Yomikura.font" }
}
