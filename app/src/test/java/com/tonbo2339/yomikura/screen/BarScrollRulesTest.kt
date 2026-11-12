package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BarScrollRulesTest {
    @Test fun lockedWhenAppPageOrOffOrSpeaking() {
        assertFalse(BarScrollRules.locked(appPage = false, hideOnScroll = true, speaking = false))
        assertTrue(BarScrollRules.locked(true, true, false))
        assertTrue(BarScrollRules.locked(false, false, false))
        assertTrue(BarScrollRules.locked(false, true, true))
    }

    @Test fun followsOnlyOnPlainTopBarPages() {
        assertTrue(BarScrollRules.follows(false, false, false, false, true, false))
        assertFalse(BarScrollRules.follows(true, false, false, false, true, false))
        assertFalse(BarScrollRules.follows(false, true, false, false, true, false))
        assertFalse(BarScrollRules.follows(false, false, true, false, true, false))
        assertFalse(BarScrollRules.follows(false, false, false, true, true, false))
        assertFalse(BarScrollRules.follows(false, false, false, false, false, false))
        assertFalse(BarScrollRules.follows(false, false, false, false, true, true))
    }

    @Test fun shownHeightIsFractionalAndClamped() {
        assertEquals(100.5f, BarScrollRules.shownHeight(100f, 0.5f, 200f), 0f)
        assertEquals(0f, BarScrollRules.shownHeight(10f, -50f, 200f), 0f)
        assertEquals(200f, BarScrollRules.shownHeight(190f, 50f, 200f), 0f)
    }

    @Test fun touchDeltaIsRebasedWhenFingerCountChanges() {
        val t = TouchDelta()
        t.down(500f)
        assertEquals(-10f, t.move(490f, 1), 0f)
        // 2 本目を置いた: 1 本目の位置のまま。差は 0
        assertEquals(0f, t.move(490f, 2), 0f)
        // 1 本目を離し、残った指 (離れた位置) だけになった: 飛びを動きにしない
        assertEquals(0f, t.move(900f, 1), 0f)
        assertEquals(5f, t.move(905f, 1), 0f)
    }

    @Test fun touchDeltaResetsOnDown() {
        val t = TouchDelta()
        t.down(100f); t.move(150f, 2)
        t.down(300f)
        assertEquals(1f, t.move(301f, 1), 0f)
    }

    @Test fun startGateWaitsAndKeepsDirection() {
        val g = StartGate(6f)
        assertNull(g.feed(2f))
        assertNull(g.feed(2f))
        assertEquals(6f, g.feed(2f)!!, 0f)
        assertNull(g.feed(-3f))
        assertNull(g.feed(3f)) // 向きが変わると数え直す
        assertEquals(-7f, g.feed(-7f)!!, 0f)
    }

    @Test fun startGateResets() {
        val g = StartGate(6f)
        g.feed(5f); g.reset()
        assertNull(g.feed(5f))
    }

    @Test fun edgeZoneAndReach() {
        val dp = 1f
        assertTrue(EdgeReveal.inZone("left", 10f, 500f, 1000, 0, 64f * dp))
        assertFalse(EdgeReveal.inZone("left", 80f, 500f, 1000, 0, 64f))
        assertTrue(EdgeReveal.inZone("right", 990f, 500f, 1000, 0, 64f))
        assertTrue(EdgeReveal.inZone("top", 500f, 100f, 1000, 60, 64f))
        assertFalse(EdgeReveal.inZone("top", 500f, 200f, 1000, 60, 64f))
        assertTrue(EdgeReveal.reached("left", 40f, 5f, 28f))
        assertFalse(EdgeReveal.reached("left", -40f, 5f, 28f))
        assertTrue(EdgeReveal.reached("right", -40f, 5f, 28f))
        assertTrue(EdgeReveal.reached("top", 5f, 40f, 28f))
        assertFalse(EdgeReveal.reached("top", 50f, 40f, 28f)) // 横ぶれのほうが大きい
    }
}
