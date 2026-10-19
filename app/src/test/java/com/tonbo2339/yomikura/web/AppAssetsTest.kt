package com.tonbo2339.yomikura

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAssetsTest {
    @Test fun `ページの部品のフォルダは include でだけ読む`() {
        assertTrue(AppAssets.isIncludeOnly("settings/style.css"))
        assertTrue(AppAssets.isIncludeOnly("list/state.js"))
        assertTrue(AppAssets.isIncludeOnly("settings/pc/x.html"))
    }

    @Test fun `ページ本体・js・icons は直接読める`() {
        assertFalse(AppAssets.isIncludeOnly("list.html"))
        assertFalse(AppAssets.isIncludeOnly("app.js"))
        assertFalse(AppAssets.isIncludeOnly("js/core/base.js"))
        assertFalse(AppAssets.isIncludeOnly("icons/green.svg"))
    }
}
