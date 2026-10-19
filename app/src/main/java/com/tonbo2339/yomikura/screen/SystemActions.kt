package com.tonbo2339.yomikura

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** Android 本体の画面を開く操作 (アプリの更新のインストール・関係する設定画面)。アプリ内のページの状態には触れない。 */
object SystemActions {
    private const val TAG = "Yomikura"

    /** ダウンロードした APK をパッケージインストーラーで開く (確認の画面は Android が出す)。 */
    fun launchInstaller(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            Diag.w(TAG, "no installer", e)
            Messages.show(activity, "インストールの画面を開けませんでした")
        }
    }

    /** 設定画面のボタンから、関係する Android の設定画面へ移る。開けなければアプリ情報へ。 */
    fun openSettings(activity: Activity, kind: String) {
        val pkg = Uri.parse("package:${activity.packageName}")
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        val intent = when (kind) {
            // 「デフォルトで開く」(Android 12+)。ここで対応リンクを追加すると、ほかのアプリのリンクがこのアプリで開く
            "links" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkg) else appInfo
            else -> appInfo
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            activity.startActivity(appInfo)
        }
    }
}
