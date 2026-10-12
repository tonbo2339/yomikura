package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun 非公開のリポジトリは404かログイン画面でlogin() {
        assertEquals("login", Updater.earlyResult(404, "", true)?.state)
        assertEquals("login", Updater.earlyResult(302, "https://github.com/login?return_to=x", true)?.state)
    }

    @Test
    fun 公開のリポジトリの404はログインではなくリリースが無い() {
        val c = Updater.earlyResult(404, "", false)!!
        assertEquals("error", c.state)
        assertEquals("公開されたリリースがありません", c.message)
        assertNull(Updater.earlyResult(302, "https://github.com/login?return_to=x", false)) // 公開なら /login への転送は先へ進む (タグが無ければ別の結果になる)
    }

    @Test
    fun タグへの転送は先へ進み_それ以外の応答はエラー() {
        assertNull(Updater.earlyResult(302, "https://github.com/o/r/releases/tag/v1.0.0", true))
        assertNull(Updater.earlyResult(302, "https://github.com/o/r/releases/tag/v1.0.0", false))
        assertEquals("error", Updater.earlyResult(500, "", true)?.state)
        assertEquals("error", Updater.earlyResult(200, "", false)?.state)
    }

    @Test
    fun newerVersions() {
        assertTrue(Updater.isNewer("0.17.0", "0.16.0"))
        assertTrue(Updater.isNewer("0.16.10", "0.16.9")) // 数字として比べる (文字の並びではない)
        assertTrue(Updater.isNewer("1.0", "0.99.9"))
        assertTrue(Updater.isNewer("0.16.1", "0.16"))
    }

    @Test
    fun sameOrOlder() {
        assertFalse(Updater.isNewer("0.16.0", "0.16.0"))
        assertFalse(Updater.isNewer("0.16", "0.16.0")) // 足りない桁は 0
        assertFalse(Updater.isNewer("0.15.9", "0.16.0"))
    }

    @Test
    fun プレリリースは同じ数字の正式版より古い() {
        assertFalse(Updater.isNewer("1.2.3-beta", "1.2.3")) // 正式版が入っているのにベータへは更新しない
        assertTrue(Updater.isNewer("1.2.3", "1.2.3-beta"))
        assertTrue(Updater.isNewer("1.2.4-beta", "1.2.3"))
        assertFalse(Updater.isNewer("1.2.3-beta", "1.2.3-beta"))
        assertTrue(Updater.isNewer("1.2.3-beta.2", "1.2.3-beta.1"))
        assertFalse(Updater.isNewer("1.2.3+build5", "1.2.3")) // ビルド情報は無視
    }

    @Test
    fun 転送先は_https_かつgithubの配下だけ() {
        assertTrue(Updater.isAllowedRedirect("https://github.com/o/r/releases/download/v1/a.apk"))
        assertTrue(Updater.isAllowedRedirect("https://objects.githubusercontent.com/x?y=1"))
        assertFalse(Updater.isAllowedRedirect("http://github.com/o/r"))
        assertFalse(Updater.isAllowedRedirect("https://evil.example.com/a.apk"))
        assertFalse(Updater.isAllowedRedirect("https://githubusercontent.com.evil.example/a"))
        assertFalse(Updater.isAllowedRedirect("not a url"))
    }
}
