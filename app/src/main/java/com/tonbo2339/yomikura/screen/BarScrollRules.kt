package com.tonbo2339.yomikura

/**
 * 上のバーのスクロール連動の、View に依存しない決まり (単体テスト用)。HeaderScroll と MainActivity が使う。
 */
object BarScrollRules {
    /** 動かさない画面か (アプリ内ページ / 設定がオフ / 読み上げ中。読み上げ中は段落に合わせてページが動き続けるので、隠さない) */
    fun locked(appPage: Boolean, hideOnScroll: Boolean, speaking: Boolean) = appPage || !hideOnScroll || speaking

    /** いま、スクロール・指の動きに合わせて上のバーを動かす画面か (onWebScroll が HeaderScroll に渡す条件と、指の動きの条件を 1 か所にまとめる) */
    fun follows(reader: Boolean, siteBarHidden: Boolean, expectVertical: Boolean, edgeOn: Boolean, onTop: Boolean, locked: Boolean) =
        !reader && !siteBarHidden && !expectVertical && !edgeOn && onTop && !locked

    /** バーの見えている高さ cur (px) を、指が dy (下が +) 動いた分だけ変えた値。0〜full に収める (小数のまま) */
    fun shownHeight(cur: Float, dy: Float, full: Float): Float = (cur + dy).coerceIn(0f, full)
}

/**
 * 指の動き (画面の座標の縦) の、1 回ごとの差。指の数が変わった回は、差を 0 にして基準だけ取り直す
 * (rawY は 1 本目の指の位置。2 本目を置く・1 本目を離すと、別の指の位置に飛ぶので、そのまま引くと大きな動きに見えて、バーが飛ぶ)。
 */
class TouchDelta {
    private var last = 0f
    private var count = 1

    fun down(raw: Float) { last = raw; count = 1 }

    fun move(raw: Float, pointerCount: Int): Float {
        val d = if (pointerCount != count) 0f else raw - last
        last = raw
        count = pointerCount
        return d
    }
}

/** 動き出しに要る、同じ向きの動きの積算 (向きが変わると数え直す)。足りない間は null、超えたら積算を返して空にする */
class StartGate(private val thresholdPx: Float) {
    private var accum = 0f

    fun reset() { accum = 0f }

    fun feed(dy: Float): Float? {
        if ((accum > 0f) != (dy > 0f)) accum = 0f
        accum += dy
        if (kotlin.math.abs(accum) < thresholdPx) return null
        val r = accum
        accum = 0f
        return r
    }
}
