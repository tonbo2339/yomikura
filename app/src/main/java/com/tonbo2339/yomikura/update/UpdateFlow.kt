package com.tonbo2339.yomikura

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 設定画面の「アプリの更新」の進行: 確認 → (インストールの許可) → ダウンロード → 署名の確認 → インストーラーを開く。
 * 結果や進み具合は、開いている画面の nhUpdate(JSON) へ渡す ({state: error / permission / progress / installing / …})。
 * 通信・署名の確認そのものは Updater。ここは、画面とのやり取りと、同時に 2 つ走らせない決まりだけを持つ。
 * 呼ぶ側 (StoreBridge) が、アプリ内ページからだけ受け付ける確認を済ませてから呼ぶ (どちらも画面のスレッドから)。
 */
class UpdateFlow(private val host: BridgeHost) {
    private val ctx get() = host.activity
    /** アプリの更新のダウンロード・インストールを同時に 2 つ走らせない (ボタンの連打対策) */
    private val installing = AtomicBoolean(false)

    /** アプリの更新を確認する。結果は nhUpdate へ。 */
    fun check() {
        if (BuildConfig.DEBUG) {
            emit(error("debug 版はアプリ内の更新を使えません (リリース版とは別のアプリです)"))
            return
        }
        val ua = host.userAgent()
        runBackground("Yomikura-update") {
            val r = try {
                checkJson(host.updater.check(ua, BuildConfig.VERSION_NAME))
            } catch (e: Exception) {
                error("確認できませんでした (${e.message ?: e.javaClass.simpleName})")
            }
            emit(r)
        }
    }

    /** 最後に確認で見つけた新しい版をダウンロードして、インストールを始める。連打しても同時に 2 つは走らせない。 */
    fun install() {
        // 「このアプリからのインストール」を許可していなければ、先にその設定画面へ (許可したらもう一度押してもらう)
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            emit(JSONObject().put("state", "permission"))
            // その設定画面の無い端末もある (落ちないよう、開けなければ知らせるだけ)
            try {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
            } catch (e: ActivityNotFoundException) {
                emit(error("インストールの許可の画面を開けませんでした。Android の設定で、このアプリからのインストールを許可してください"))
            }
            return
        }
        if (!installing.compareAndSet(false, true)) return
        val ua = host.userAgent()
        runBackground("Yomikura-update") {
            try {
                emit(progress(0))
                // 画面が覚えていた結果から入れるとき (アプリを開き直したあと) は、APK の場所を探し直す
                if (!host.updater.hasApk) {
                    val c = host.updater.check(ua, BuildConfig.VERSION_NAME)
                    if (c.state != "available") {
                        // 探し直したら入れられる版が無かった (最新になっていた・リリースが消えた・ログイン切れなど)。その結果を画面に伝えて止める
                        emit(checkJson(c))
                        return@runBackground
                    }
                }
                val apk = host.updater.download(ua) { pct -> emit(progress(pct)) }
                val problem = host.updater.verify(apk)
                if (problem != null) {
                    emit(error(problem))
                } else {
                    ctx.runOnUiThread { host.launchInstaller(apk) }
                    emit(JSONObject().put("state", "installing"))
                }
            } catch (e: Exception) {
                emit(error(e.message ?: "ダウンロードできませんでした"))
            } finally {
                installing.set(false)
            }
        }
    }

    private fun checkJson(c: Updater.Check) = JSONObject().put("state", c.state).put("latest", c.latest).put("message", c.message)
    private fun error(message: String) = JSONObject().put("state", "error").put("message", message)
    private fun progress(pct: Int) = JSONObject().put("state", "progress").put("pct", pct)

    /** 更新の結果や進み具合を、開いている画面 (設定) の nhUpdate(JSON) へ渡す。どのスレッドからでも呼べる。 */
    private fun emit(json: JSONObject) = host.emitJs("nhUpdate", json)
}
