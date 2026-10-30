package com.tonbo2339.yomikura

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.Calendar

/**
 * 裏での更新の確認と通知。
 *
 * 設定 (settings.bgCheck):
 *   {on, mode: 'interval' | 'times', hours, quietOn, quietFrom: 'HH:MM', quietTo: 'HH:MM', times: ['HH:MM'], days: [0..6 (日..土)]}
 *   - interval: 「静かな時間」の終わり (無ければ 0:00) から hours 時間ごと。静かな時間の中は飛ばす
 *   - times: 指定した時刻だけ
 *
 * 流れ: AlarmManager (時刻どおり。USE_EXACT_ALARM) → BgAlarmReceiver → BgCheckJob (JobScheduler) が画面に出さない WebView で
 * いつもの巡回 (#nh-collect-bg) を動かす → ui.js が NHStore.bgDone(結果) → 前回から話が増えた作品があれば通知。
 * 通知しないとき: 新着が無い / アプリを開いている / アプリの画面で確認してから 1 時間以内。
 */
object BgCheck {
    const val TAG = "Yomikura.bg"
    private const val JOB_ID = 2339
    const val ACTION_ALARM = "com.tonbo2339.yomikura.BG_ALARM"
    const val ACTION_OPEN_LIST = "com.tonbo2339.yomikura.OPEN_LIST"
    private const val KEY_NEXT = "bgNextAt"
    /** これより前に確認が終わっていれば、裏での確認自体を省く (同じ結果になるだけなので) */
    private const val SKIP_IF_CHECKED_MS = 10 * 60_000L
    /** アプリの画面で確認してからこの間は、裏で見つけても通知しない */
    private const val QUIET_AFTER_FG_MS = 60 * 60_000L

    /** アプリを前面で使っているか (MainActivity の onResume / onPause) */
    @Volatile var foreground = false
    /** 裏での確認が保存データを書き換えたとき、開いている画面 (バッジなど) に知らせる */
    @Volatile var onDataChanged: (() -> Unit)? = null
    /** 裏での確認で、サイトが確認画面で止まったとき、開いている画面に知らせる (画面が無ければ通知を出す) */
    @Volatile var onChallenge: ((String) -> Unit)? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Edition.PREFS_NAME, Context.MODE_PRIVATE)
    private fun data(ctx: Context): JSONObject = NHData.obj(ctx)
    private fun config(ctx: Context): JSONObject? = NHData.settingsOf(data(ctx)).optJSONObject("bgCheck")

    // ---- 時刻の計算 ----

    private val HHMM = Regex("^(\\d{1,2}):(\\d{2})$")

    private fun minutesOf(hhmm: String?): Int? {
        val m = HHMM.find(hhmm?.trim().orEmpty()) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }

    /** 「時刻で」確認するときの、時刻どうしの最小の間隔 (分)。サイトへのアクセスを控えるため。設定画面 (core/bg-time.js の NH.BG_MIN_GAP) と同じ */
    const val MIN_GAP = 30

    /** 昇順の時刻 (分) から、前の時刻と MIN_GAP 分未満しかあいていないものを除く。0:00 をまたぐ最後と最初の間隔も見る */
    internal fun spaced(sorted: List<Int>): List<Int> {
        val out = ArrayList<Int>()
        for (m in sorted) if (out.isEmpty() || m - out.last() >= MIN_GAP) out.add(m)
        while (out.size > 1 && out.first() + 1440 - out.last() < MIN_GAP) out.removeAt(out.size - 1)
        return out
    }

    /** 1 日のうち確認する時刻 (0:00 からの分、昇順)。「時刻で」は、MIN_GAP 分未満で近い時刻を除く。 */
    fun slots(c: JSONObject): List<Int> {
        if (c.optString("mode") == "times") {
            val t = c.optJSONArray("times") ?: JSONArray()
            return spaced((0 until t.length()).mapNotNull { minutesOf(t.optString(it)) }.distinct().sorted())
        }
        val step = (c.optInt("hours", 3).coerceIn(1, 24)) * 60
        val quiet = c.optBoolean("quietOn")
        val from = minutesOf(c.optString("quietFrom")) ?: 0
        val to = minutesOf(c.optString("quietTo")) ?: 0
        val inQuiet = { m: Int -> quiet && from != to && (if (from < to) m in from until to else m >= from || m < to) }
        val anchor = if (quiet) to else 0
        return (0 until (1440 + step - 1) / step).map { (anchor + it * step) % 1440 }.filterNot(inQuiet).distinct().sorted()
    }

    /** 確認する曜日 (Calendar.SUNDAY = 1 .. SATURDAY = 7)。未設定なら毎日。 */
    private fun days(c: JSONObject): Set<Int> {
        val d = c.optJSONArray("days") ?: return (1..7).toSet()
        return (0 until d.length()).map { d.optInt(it) + 1 }.filter { it in 1..7 }.toSet()
    }

    /** now より後の、次に確認する時刻 (無ければ null)。 */
    fun nextAt(c: JSONObject, now: Long = System.currentTimeMillis()): Long? {
        val slots = slots(c)
        val days = days(c)
        if (slots.isEmpty() || days.isEmpty()) return null
        val cal = Calendar.getInstance()
        for (offset in 0..7) {
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, offset)
            if (cal.get(Calendar.DAY_OF_WEEK) !in days) continue
            for (m in slots) {
                cal.set(Calendar.HOUR_OF_DAY, m / 60); cal.set(Calendar.MINUTE, m % 60)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis > now + 30_000) return cal.timeInMillis
            }
        }
        return null
    }

    /** 次のアラームの時刻 (ミリ秒)。裏での確認がオフ・掛けていなければ 0 */
    fun nextAlarmAt(ctx: Context): Long = if (config(ctx)?.optBoolean("on") == true) prefs(ctx).getLong(KEY_NEXT, 0L) else 0L

    /** 設定画面に出す内容: {on, slots: ['HH:MM'], next: 時刻 (ms) | 0, notify: 通知が許可されているか}。 */
    fun plan(ctx: Context): String {
        val c = config(ctx) ?: JSONObject()
        val slots = JSONArray(slots(c).map { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" })
        val next = if (c.optBoolean("on")) prefs(ctx).getLong(KEY_NEXT, 0L) else 0L
        return JSONObject().put("on", c.optBoolean("on")).put("slots", slots).put("next", next).put("notify", Notices.allowed(ctx)).toString()
    }

    private fun alarmIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, BgAlarmReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** 次の確認のアラームを掛け直す (設定が変わったとき・確認のあと・再起動のあと)。 */
    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val c = config(ctx)
        val next = if (c != null && c.optBoolean("on")) nextAt(c) else null
        if (next == null) {
            am.cancel(alarmIntent(ctx))
            prefs(ctx).edit().remove(KEY_NEXT).apply()
            DevLog.eventOf(DevLog.Category.CHECK, "bg alarm off")
            return
        }
        // 時刻どおりに鳴らす (USE_EXACT_ALARM。ストア外のアプリはインストール時に許可される)。
        // 使えない端末では 10 分の幅で掛ける (おおよその時刻のアラームは、Android が 1 時間近く遅らせることがあるため使わない)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent(ctx))
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, next, 10 * 60_000L, alarmIntent(ctx))
        }
        prefs(ctx).edit().putLong(KEY_NEXT, next).apply()
        Log.d(TAG, "next ${java.util.Date(next)}")
        DevLog.eventOf(DevLog.Category.CHECK, "bg alarm set", "at" to DevLogFormat.time(next))
    }

    /** 確認を今すぐ始める (アラームから)。画面のボタンからは呼ばない (連打すると、クールダウン無しで何度もサイトへアクセスしてしまうため)。 */
    fun startNow(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        // 同じ ID で schedule し直すと、動いている確認が止められる (onStopJob)。予約済み・実行中なら、それに任せる
        if (js.getPendingJob(JOB_ID) != null) {
            Log.d(TAG, "startNow: already scheduled or running")
            DevLog.eventOf(DevLog.Category.CHECK, "bg job", "result" to "already scheduled or running")
            return
        }
        val job = { expedited: Boolean ->
            JobInfo.Builder(JOB_ID, ComponentName(ctx, BgCheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .apply { if (expedited && Build.VERSION.SDK_INT >= 31) setExpedited(true) }
                .build()
        }
        // 急ぎのジョブにすると、省電力中でもすぐ動く (使える回数に上限があるので、だめなら普通のジョブで)
        if (Build.VERSION.SDK_INT >= 31 && js.schedule(job(true)) == JobScheduler.RESULT_SUCCESS) {
            DevLog.eventOf(DevLog.Category.CHECK, "bg job", "result" to "scheduled", "expedited" to true)
            return
        }
        val ok = js.schedule(job(false)) == JobScheduler.RESULT_SUCCESS
        DevLog.eventOf(DevLog.Category.CHECK, "bg job", "result" to if (ok) "scheduled" else "refused", "expedited" to false)
    }

    // ---- 通知 ----

    val SITE_NAMES = mapOf("narou" to "なろう", "narou18" to "R18", "hameln" to "ハーメルン", "kakuyomu" to "カクヨム")
    /** サイトのキーの表示名 (知らないキーはそのまま) */
    fun siteName(id: String): String = SITE_NAMES[id] ?: id
    private val ERR_TEXT = mapOf("challenge" to "確認画面", "login" to "ログイン切れ?", "blocked" to "アクセス制限", "error" to "取得エラー", "rest" to "休止中")

    /**
     * 巡回の結果 (collectLog = {サイト: {n, added, fresh} | {err}})。
     * added = リストに新しく入った作品の数、fresh = 前回から話が増えた作品の名前、errors = 「サイト (理由)」、challenge = 確認画面で止まったサイトのキー。
     */
    class Result(val added: Int, val fresh: List<String>, val errors: List<String>, val challenge: List<String>, val login: List<String> = emptyList())

    fun parse(logJson: String): Result {
        val log = logJson.toJsonOrEmpty()
        var added = 0
        val titles = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val challenge = mutableListOf<String>()
        val login = mutableListOf<String>()
        log.keys().forEach { id ->
            val e = log.optJSONObject(id) ?: return@forEach
            val err = e.optString("err")
            if (err.isNotEmpty()) errors += "${siteName(id)} (${ERR_TEXT[err] ?: err})"
            if (err == "challenge") challenge += id
            if (err == "login") login += id
            added += e.optInt("added")
            val f = e.optJSONArray("fresh") ?: return@forEach
            for (i in 0 until f.length()) f.optString(i).takeIf { it.isNotBlank() }?.let { titles += it }
        }
        return Result(added, titles, errors, challenge, login)
    }

    /** 巡回の結果 (collectLog) から、通知するか決めて出す。 */
    fun finish(ctx: Context, logJson: String) {
        val r = parse(logJson)
        val titles = r.fresh
        val errors = r.errors
        val sinceFg = System.currentTimeMillis() - data(ctx).optLong("lastCollectFg", 0L)
        Log.d(TAG, "done: fresh=${titles.size} errors=$errors fg=$foreground sinceFg=${sinceFg / 60000}min")
        DevLog.eventOf(DevLog.Category.CHECK, "bg done", "fresh" to titles.size, "errors" to errors.joinToString(",").ifEmpty { null }, "inApp" to foreground, "sinceFgMin" to sinceFg / 60000)
        if (titles.isEmpty() || foreground || sinceFg < QUIET_AFTER_FG_MS || !Notices.allowed(ctx)) return

        val body = titles.joinToString("\n") + (if (errors.isNotEmpty()) "\n確認できなかった: " + errors.joinToString("、") else "")
        val n = Notices.builder(ctx, Notices.Channel.UPDATES)
            .setContentTitle("${titles.size} 作品に新しい話")
            .setContentText(titles.joinToString("、"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(Notices.openApp(ctx, 0, ACTION_OPEN_LIST))
            .setAutoCancel(true)
            .build()
        Notices.post(ctx, Notices.Id.UPDATE, n)
    }

    /**
     * 裏での確認で、サイトが確認画面で止まった。アプリを開いていれば画面で尋ね、閉じていれば通知を出す (タップするとそのサイトのページが開く)。
     * 次の確認までは、止まったサイトを取りに行かない (ui.js の markRest) ので、同じ知らせが続けて出ることはない。
     */
    fun challenged(ctx: Context, site: String) {
        Log.d(TAG, "challenge: $site fg=$foreground")
        val hook = onChallenge
        if (foreground && hook != null) { hook(site); return }
        val page = SiteConfig.COLLECT_PAGES[site]
        if (!Notices.allowed(ctx) || page == null) return
        val name = siteName(site)
        val n = Notices.builder(ctx, Notices.Channel.UPDATES)
            .setContentTitle("$name で確認画面が出ました")
            .setContentText("タップしてサイトを開き、通してください。そのあと、もう一度更新を確認してください")
            .setContentIntent(Notices.openApp(ctx, 1, Intent.ACTION_VIEW, page))
            .setAutoCancel(true)
            .build()
        Notices.post(ctx, Notices.Id.CHALLENGE, n)
    }

    /** 確認を省くか (直前に確認したばかり。始めた時刻も数える = 途中で止まった・止められた確認のすぐあとにも、サイトへ続けて行かない)。 */
    fun recentlyChecked(ctx: Context): Boolean =
        System.currentTimeMillis() - CollectCooldown.lastOf(data(ctx)) < SKIP_IF_CHECKED_MS
}

/** アラーム (次の確認の時刻): 確認を始めて、次のアラームを掛ける。非公開 (このアプリのアラームからだけ届く)。 */
class BgAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Diag.init(ctx) // アラームでプロセスが起きたときも、開発用ログに残せるように
        // 確認を始められなくても、次のアラームは必ず掛け直す (でないと、以後の確認が途絶える)
        if (intent.action == BgCheck.ACTION_ALARM) {
            DevLog.eventOf(DevLog.Category.CHECK, "bg alarm fired")
            try { BgCheck.startNow(ctx) } catch (e: Exception) { Diag.e(BgCheck.TAG, "startNow", e) }
        }
        BgCheck.reschedule(ctx)
    }
}

/** 端末の再起動 / アプリの更新のあと: アラームは消えているので掛け直す。 */
class BgBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) BgCheck.reschedule(ctx)
    }
}

/** 画面に出さない WebView で巡回を動かすジョブ (中身は Collector)。 */
class BgCheckJob : JobService() {
    private var collector: Collector? = null

    // 裏の処理で例外が出るとアプリごと落ちる (画面を使っている最中でも) ので、ここで止めて確認を打ち切る
    override fun onStartJob(p: JobParameters): Boolean = try {
        Diag.init(this)
        // 2 つの仕事 (外への送信・更新の確認) が両方終わったらジョブを終える (先に終えると、送っている途中で止められうる)
        val pending = AtomicInteger(2)
        val doneOne = { if (pending.decrementAndGet() == 0) jobFinished(p, false) }
        // 外への送信: 時期なら。送らないなら、その分を先に終えたことにする
        if (!Edition.autoExtrasInBackground(this) { doneOne() }) pending.decrementAndGet()
        val startUrl = SiteConfig.collectStartUrl(NHData.obj(this), "bg")
        val skip = when {
            BgCheck.recentlyChecked(this) -> "checked recently"
            Collector.anyAlive() -> "checking in the app" // 画面での確認が動いている (確認画面で待っている分も。同じサイトへ 2 つの WebView から続けてアクセスしない)
            startUrl == null -> "no site to check (or all resting)"
            else -> null
        }
        DevLog.eventOf(DevLog.Category.CHECK, if (skip != null) "bg skip" else "bg run", "reason" to skip, "sinceLastMin" to (System.currentTimeMillis() - CollectCooldown.lastOf(NHData.obj(this))) / 60_000L)
        if (skip != null) {
            Log.d(BgCheck.TAG, "skip: $skip")
            pending.decrementAndGet() > 0 // 外への送信がまだなら、終わったときに jobFinished
        } else {
            CollectCooldown.markStarted(this)
            collector = Collector(this, Edition.hostFilter(this), startUrl!!, onChallenge = { site, _ -> BgCheck.challenged(this, site) }) { log ->
                collector = null
                try { if (log != null) BgCheck.finish(this, log) } catch (e: Exception) { Diag.e(BgCheck.TAG, "finish", e) }
                doneOne()
            }
            true
        }
    } catch (e: Exception) {
        Diag.e(BgCheck.TAG, "start failed", e)
        collector?.cancel()
        collector = null
        false
    }

    override fun onStopJob(p: JobParameters): Boolean {
        Diag.w(BgCheck.TAG, "stopped by system")
        val running = collector != null
        collector?.cancel()
        collector = null
        // 確認の途中で止められたら、あとでやり直してもらう (true)。やり直しは、始めてから 10 分たつまでは何もせずに終わる
        // (recentlyChecked は始めた時刻も数える。止められた直後に、取ったばかりのサイトへ続けて行かない)
        return running
    }
}
