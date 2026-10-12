package com.tonbo2339.yomikura

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * オフライン保存の待ち行列。保存する作品と、選んだ話を、順番どおりに並べて端末に残す。
 * アプリや端末が止まっても、ここを読めば続きから進められる (保存済みの話は OfflineStore 側で分かる)。
 *
 * ファイルの読み書きだけ [file] に頼り、並べ替えなどの中身は Android に依存しない (単体テストできる)。
 * 書き込みは一時ファイルを経由して、途中で壊れないようにする (OfflineStore と同じ)。
 */
class SaveQueue(private val file: File) {

    enum class Status {
        WAITING,      // 順番待ち
        RUNNING,      // いま保存している
        WAIT_WIFI,    // 「Wi-Fi のときだけ」の設定で、Wi-Fi になるのを待っている
        PAUSED,       // 利用者が一時停止した
        DONE,         // すべて保存した
        STOPPED,      // 中止した、または続けて失敗して止まった
        NEEDS_CHECK,  // 確認画面が出た。アプリで開いて通してもらう必要がある
        ;

        /** まだ終わっていない (キューに残して、順番に進める) */
        val active: Boolean get() = this == WAITING || this == RUNNING || this == WAIT_WIFI || this == PAUSED
    }

    data class Ep(val ep: String, val url: String, val title: String)

    data class Job(
        val site: String,
        val id: String,
        val title: String,
        val url: String,
        val eps: List<Ep>,
        /** 作品の全話数 (目次の全部)。eps は選んだ話だけなので、保存の記録に使う */
        val total: Int = 0,
        /** 「Wi-Fi のときだけ」の設定がオンでも、この作品はモバイル回線で保存する (追加するときに「今回だけ」を選んだ) */
        val mobileOk: Boolean = false,
        /** 挿絵を端末に保存する (既定は含める。「文字だけ」を選んだときは false) */
        val images: Boolean = true,
        val status: Status = Status.WAITING,
        val note: String = "",
        val addedAt: Long = 0L,
    ) {
        val key: String get() = "$site:$id"
    }

    /** 全部の作品 (順番どおり)。読めないファイルなら空。同じファイルは、書き込みのあとまで読み込んだ内容を使い回す */
    fun all(): List<Job> = synchronized(LOCK) {
        val path = file.absolutePath
        cache[path]?.let { return@synchronized it }
        try {
            (if (file.isFile) decode(file.readText()) else emptyList()).also { cache[path] = it }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 作品を末尾に入れる。同じ作品が待ちの間は、話を足す (順番は変えない)。
     * 終わった作品なら、作り直して末尾へ入れる。戻り値は新しく入った話の数 (0 = 何も変わらない。書けなかったときも 0)。
     */
    fun add(job: Job): Int = synchronized(LOCK) {
        if (!isSafe(job.site) || !isSafe(job.id)) return@synchronized 0
        val jobs = all().toMutableList()
        val i = jobs.indexOfFirst { it.key == job.key }
        if (i >= 0 && jobs[i].status.active) {
            val have = jobs[i].eps.map { it.ep }.toSet()
            val fresh = job.eps.filter { it.ep !in have }
            if (fresh.isEmpty()) return@synchronized 0
            jobs[i] = jobs[i].copy(eps = jobs[i].eps + fresh, mobileOk = jobs[i].mobileOk || job.mobileOk)
            return@synchronized if (write(jobs)) fresh.size else 0
        }
        if (i >= 0) jobs.removeAt(i)
        jobs.add(job.copy(status = Status.WAITING, note = ""))
        if (write(jobs)) job.eps.size else 0
    }

    /** 作品の状態や話を書き換える。無い作品なら何もしない。書けたら true */
    fun update(key: String, change: (Job) -> Job): Boolean = synchronized(LOCK) {
        val jobs = all().toMutableList()
        val i = jobs.indexOfFirst { it.key == key }
        if (i < 0) return@synchronized false
        jobs[i] = change(jobs[i])
        write(jobs)
    }

    /** キューに作品があるか (保存を消した作品に、サービスが書き戻さないための確認) */
    fun contains(key: String): Boolean = all().any { it.key == key }

    fun remove(key: String): Boolean = synchronized(LOCK) { write(all().filter { it.key != key }) }

    /** キューを空にする (オフライン保存をすべて消すとき) */
    fun clearAll(): Boolean = synchronized(LOCK) { write(emptyList()) }

    /** 作品を 1 つ上 (delta = -1) か下 (delta = 1) に動かす。実行中の作品と、いま保存しているサービスの作品 ([current]) は動かさない */
    fun move(key: String, delta: Int, current: String? = null) {
        synchronized(LOCK) {
            val jobs = all().toMutableList()
            val i = jobs.indexOfFirst { it.key == key }
            val j = i + delta
            if (i < 0 || j !in jobs.indices) return
            if (jobs[i].status == Status.RUNNING || jobs[j].status == Status.RUNNING) return
            if (current != null && (jobs[i].key == current || jobs[j].key == current)) return // 一時停止中でも、今の作品は動かさない
            val moved = jobs[i]
            jobs[i] = jobs[j]
            jobs[j] = moved
            write(jobs)
        }
    }

    /** 次に保存する作品 (待ちの一番上)。Wi-Fi 待ちと一時停止は含めない */
    fun next(): Job? = all().firstOrNull { it.status == Status.WAITING }

    /** 終わった作品をキューから消す */
    fun clearFinished(): Boolean = synchronized(LOCK) { write(all().filter { it.status.active }) }

    /** アプリが止まったあとの立て直し: 保存中だった作品は、順番待ちに戻す */
    fun recoverAfterRestart() {
        synchronized(LOCK) {
            if (all().none { it.status == Status.RUNNING }) return
            write(all().map { if (it.status == Status.RUNNING) it.copy(status = Status.WAITING) else it })
        }
    }

    /**
     * ファイルへ書く。書けたら true。書けなかったときは false を返し、読み込んだ内容の使い回しは捨てる
     * (次に読むときはファイルの内容に戻るので、メモリ上だけ新しい状態にはならない)。LOCK を持って呼ぶこと
     */
    private fun write(jobs: List<Job>): Boolean {
        val path = file.absolutePath
        return try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(encode(jobs))
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            cache[path] = jobs
            true
        } catch (e: Exception) {
            cache.remove(path)
            false
        }
    }

    companion object {
        /** キューを置くファイル名 (filesDir の直下)。サービスと画面の両方がこの名前を使う */
        const val FILE_NAME = "offline_queue.json"

        /** 作品の ID はフォルダ名に使う (OfflineStore と同じ決まり) */
        private val SAFE = Regex("^[A-Za-z0-9_-]{1,40}$")

        /** 読み込んだキューの内容 (ファイルごと)。画面とサービスが別の SaveQueue で同じファイルを扱うので、置き場は共有する */
        private val cache = HashMap<String, List<Job>>()

        /** 画面とサービスは別の SaveQueue を作るので、ロックも (インスタンスごとではなく) 共有する。cache もこのロックで守る */
        private val LOCK = Any()

        fun isSafe(s: String): Boolean = SAFE.matches(s)

        fun encode(jobs: List<Job>): String {
            val arr = JSONArray()
            jobs.forEach { j ->
                val eps = JSONArray()
                j.eps.forEach { e ->
                    eps.put(JSONObject().put("ep", e.ep).put("url", e.url).put("title", e.title))
                }
                arr.put(
                    JSONObject()
                        .put("key", j.key) // 画面 (JS) が作品を指すための名前。読み込みでは使わない (site と id から作る)
                        .put("site", j.site)
                        .put("id", j.id)
                        .put("title", j.title)
                        .put("url", j.url)
                        .put("eps", eps)
                        .put("total", j.total)
                        .put("mobileOk", j.mobileOk)
                        .put("images", j.images)
                        .put("status", j.status.name)
                        .put("note", j.note)
                        .put("addedAt", j.addedAt),
                )
            }
            return arr.toString()
        }

        /** 読めない項目は飛ばす (1 つ壊れていても、ほかの作品は残す) */
        fun decode(text: String): List<Job> {
            val arr = JSONArray(text)
            val out = ArrayList<Job>()
            for (k in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(k)
                    val eps = o.getJSONArray("eps")
                    val list = (0 until eps.length()).map { i ->
                        val e = eps.getJSONObject(i)
                        Ep(e.getString("ep"), e.getString("url"), e.optString("title"))
                    }
                    val site = o.getString("site")
                    val id = o.getString("id")
                    if (!isSafe(site) || !isSafe(id)) continue
                    out.add(
                        Job(
                            site = site,
                            id = id,
                            title = o.optString("title"),
                            url = o.getString("url"),
                            eps = list,
                            total = o.optInt("total"),
                            mobileOk = o.optBoolean("mobileOk"),
                            images = o.optBoolean("images", true),
                            status = Status.valueOf(o.getString("status")),
                            note = o.optString("note"),
                            addedAt = o.optLong("addedAt"),
                        ),
                    )
                } catch (e: Exception) {
                    // 読めない作品は飛ばす
                }
            }
            return out
        }
    }
}
