package com.tonbo2339.yomikura

import com.tonbo2339.yomikura.SaveQueue.Ep
import com.tonbo2339.yomikura.SaveQueue.Job
import com.tonbo2339.yomikura.SaveQueue.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SaveQueueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun queue() = SaveQueue(java.io.File(tmp.root, "queue.json"))

    private fun job(id: String, vararg eps: String) = Job(
        site = "narou",
        id = id,
        title = "作品$id",
        url = "https://example.test/$id/",
        eps = eps.map { Ep(it, "https://example.test/$id/$it", "第${it}話") },
    )

    @Test
    // 作品は追加した順に並ぶ
    fun addKeepsOrder() {
        val q = queue()
        q.add(job("a1", "1", "2"))
        q.add(job("b2", "1"))
        assertEquals(listOf("narou:a1", "narou:b2"), q.all().map { it.key })
        assertEquals(Status.WAITING, q.all()[0].status)
    }

    @Test
    // 待ちの作品に話を足すと、足した話だけ数える
    fun addMergesEpisodesIntoWaitingJob() {
        val q = queue()
        assertEquals(2, q.add(job("a1", "1", "2")))
        assertEquals(1, q.add(job("a1", "2", "3"))) // 2 話目は重複
        assertEquals(0, q.add(job("a1", "1"))) // 増えないなら 0
        val only = q.all().single()
        assertEquals(listOf("1", "2", "3"), only.eps.map { it.ep })
        assertEquals(1, q.all().size) // 作品は 1 つのまま、順番も変わらない
    }

    @Test
    // 終わった作品は作り直して末尾へ入る
    fun finishedJobIsRequeuedAtEnd() {
        val q = queue()
        q.add(job("a1", "1"))
        q.add(job("b2", "1"))
        q.update("narou:a1") { it.copy(status = Status.DONE) }
        assertEquals(1, q.add(job("a1", "5")))
        assertEquals(listOf("narou:b2", "narou:a1"), q.all().map { it.key })
        assertEquals(Status.WAITING, q.all().last().status)
        assertEquals(listOf("5"), q.all().last().eps.map { it.ep })
    }

    @Test
    // フォルダに使えない ID は入らない
    fun unsafeIdIsRejected() {
        val q = queue()
        assertEquals(0, q.add(job("../evil", "1")))
        assertTrue(q.all().isEmpty())
    }

    @Test
    // 並べ替えは入れ替えるが、実行中の作品は動かさない
    fun moveSwapsButNotRunning() {
        val q = queue()
        q.add(job("a1", "1"))
        q.add(job("b2", "1"))
        q.add(job("c3", "1"))
        q.move("narou:c3", -1)
        assertEquals(listOf("narou:a1", "narou:c3", "narou:b2"), q.all().map { it.key })
        q.update("narou:a1") { it.copy(status = Status.RUNNING) }
        q.move("narou:a1", 1) // 実行中は動かない
        q.move("narou:c3", -1) // 実行中の作品の隣とは入れ替わらない
        assertEquals(listOf("narou:a1", "narou:c3", "narou:b2"), q.all().map { it.key })
    }

    @Test
    // 次の作品は、待ちの一番上。一時停止と Wi-Fi 待ちは飛ばす
    fun nextSkipsPausedAndWifiWait() {
        val q = queue()
        q.add(job("a1", "1"))
        q.add(job("b2", "1"))
        q.add(job("c3", "1"))
        q.update("narou:a1") { it.copy(status = Status.PAUSED) }
        q.update("narou:b2") { it.copy(status = Status.WAIT_WIFI) }
        assertEquals("narou:c3", q.next()?.key)
        q.update("narou:c3") { it.copy(status = Status.DONE) }
        assertNull(q.next())
    }

    @Test
    // 再起動のあとは、実行中の作品を順番待ちに戻す
    fun restartResetsRunningToWaiting() {
        val q = queue()
        q.add(job("a1", "1"))
        q.update("narou:a1") { it.copy(status = Status.RUNNING) }
        q.recoverAfterRestart()
        assertEquals(Status.WAITING, q.all().single().status)
    }

    @Test
    // 一時停止したまま止まった作品も、順番待ちに戻す (PAUSED のままだと、選ばれず「もう一度」もできない)
    fun recoverResetsPaused() {
        val q = queue()
        q.add(job("a1", "1"))
        q.update("narou:a1") { it.copy(status = Status.PAUSED) }
        q.recoverAfterRestart()
        assertEquals(Status.WAITING, q.all().single().status)
        assertEquals("narou:a1", q.next()?.key)
    }

    @Test
    // 終わった作品だけ消す
    fun clearFinishedKeepsActive() {
        val q = queue()
        q.add(job("a1", "1"))
        q.add(job("b2", "1"))
        q.update("narou:a1") { it.copy(status = Status.STOPPED) }
        q.clearFinished()
        assertEquals(listOf("narou:b2"), q.all().map { it.key })
    }

    @Test
    // 壊れた作品は飛ばして、ほかは残す
    fun brokenJobIsSkipped() {
        val good = SaveQueue.encode(listOf(job("a1", "1")))
        val broken = good.replace("\"status\":\"WAITING\"", "\"status\":\"NOPE\"")
        val mixed = "[" + broken.removeSurrounding("[", "]") + "," + good.removeSurrounding("[", "]") + "]"
        assertEquals(1, SaveQueue.decode(mixed).size)
        java.io.File(tmp.root, "queue.json").writeText("これは JSON ではない")
        assertTrue(queue().all().isEmpty())
        // 読めないファイルは .bad に残す (次の書き込みで消えないように)
        assertEquals("これは JSON ではない", java.io.File(tmp.root, "queue.json.bad").readText())
    }

    @Test
    // JSON に書いて読み直すと同じ中身になる
    fun roundTripKeepsContent() {
        val jobs = listOf(job("a1", "1", "2").copy(status = Status.NEEDS_CHECK, note = "確認", addedAt = 123L))
        assertEquals(jobs, SaveQueue.decode(SaveQueue.encode(jobs)))
        assertFalse(Status.DONE.active)
        assertTrue(Status.WAIT_WIFI.active)
    }

    // 待ちの作品に「今回だけモバイル」で足すと、その印が付く。キューは全部消せる
    @Test
    fun mobileOkIsMergedAndClearAllEmpties() {
        val q = queue()
        q.add(job("a1", "1"))
        assertFalse(q.all().single().mobileOk)
        q.add(job("a1", "2").copy(mobileOk = true))
        assertTrue(q.all().single().mobileOk)
        assertEquals(listOf(q.all().single()), SaveQueue.decode(SaveQueue.encode(q.all()))) // 印も JSON に残る
        q.clearAll()
        assertTrue(q.all().isEmpty())
    }

    // 画面 (JS) が作品を指す key が JSON に入る
    @Test
    fun encodedJsonHasKey() {
        val first = org.json.JSONArray(SaveQueue.encode(listOf(job("a1", "1")))).getJSONObject(0)
        assertEquals("narou:a1", first.getString("key"))
    }

    // 一時停止中の今の作品 (current) は、状態が RUNNING でなくても動かさない
    @Test
    fun moveKeepsCurrentJobInPlace() {
        val q = queue()
        q.add(job("a1", "1"))
        q.add(job("b2", "1"))
        q.update("narou:a1") { it.copy(status = Status.PAUSED) }
        q.move("narou:b2", -1, "narou:a1")
        assertEquals(listOf("narou:a1", "narou:b2"), q.all().map { it.key })
        q.move("narou:b2", -1) // 今の作品を知らせなければ動く (ほかの作品どうしの並べ替え用)
        assertEquals(listOf("narou:b2", "narou:a1"), q.all().map { it.key })
    }

    // 画面 (JS の別スレッド) と保存サービス (メインスレッド) が同時に書いても、どちらの作品も消えない
    @Test
    fun concurrentAddsAllSurvive() {
        val q = queue()
        val threads = (0 until 4).map { t ->
            Thread { for (i in 0 until 40) q.add(job("t${t}x$i", "1")) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(160, q.all().size)
    }

    // 挿絵の選び (含める / 文字だけ) は JSON に残る。古い形 (images の無い物) は含めるとして読む
    @Test
    fun imagesChoiceRoundTripsAndDefaultsToOn() {
        val jobs = listOf(job("a1", "1").copy(images = false), job("b2", "1"))
        val back = SaveQueue.decode(SaveQueue.encode(jobs))
        assertEquals(false, back[0].images)
        assertEquals(true, back[1].images)
        val old = org.json.JSONArray(SaveQueue.encode(listOf(job("c3", "1")))).getJSONObject(0).apply { remove("images") }
        assertEquals(true, SaveQueue.decode("[$old]").single().images)
    }

    @Test
    // 書けなかったときは 0 / false を返し、メモリ上だけ新しい状態にはならない
    fun writeFailureIsVisibleAndKeepsStateConsistent() {
        val blocker = tmp.newFile("blocker") // 親がファイルなので、キューのファイルは作れない
        val q = SaveQueue(java.io.File(blocker, "queue.json"))
        assertEquals(0, q.add(job("a1", "1")))
        assertTrue(q.all().isEmpty())
        assertFalse(q.contains("narou:a1"))
        assertFalse(q.remove("narou:a1"))
        assertFalse(q.update("narou:a1") { it })
    }

    @Test
    // 同じファイルを別の SaveQueue で開いても、書き込みのあとは同じ内容が見える
    fun separateInstancesShareTheSameFile() {
        val a = queue()
        val b = queue()
        a.add(job("a1", "1"))
        assertTrue(b.contains("narou:a1"))
        assertTrue(b.remove("narou:a1"))
        assertFalse(a.contains("narou:a1"))
    }

    @Test
    // 自動更新の作品: 話の一覧は空のまま入り、同じ作品がキューにあれば入れない。読み書きしても refresh は残る
    fun refreshJobIsAddedOnceAndSurvivesReload() {
        val q = queue()
        val j = job("a1").copy(refresh = true)
        assertEquals(1, q.add(j))
        assertEquals(0, q.add(j))
        assertEquals(1, q.add(job("a1", "1"))) // 手動の保存は話を足すだけ (自動更新のまま。保存を始めるとき、保存していない話は全部取る)
        val back = SaveQueue.decode(SaveQueue.encode(q.all())).single()
        assertTrue(back.refresh)
        assertEquals(1, back.eps.size)
    }

    @Test
    // 自動更新は、「Wi-Fi のときだけ」の設定に関係なく従量課金の回線では始めない (「今回だけモバイル」があれば別)
    fun refreshNeedsUnmetered() {
        assertTrue(job("a1").copy(refresh = true).needsUnmetered(false))
        assertFalse(job("a1").needsUnmetered(false))
        assertTrue(job("a1").needsUnmetered(true))
        assertFalse(job("a1").copy(mobileOk = true).needsUnmetered(true))
    }
}
