package com.tonbo2339.yomikura

import kotlin.concurrent.thread

/** 裏のスレッドで処理を動かす共通の入口 (スレッドに名前を付ける。診断で見分けるため)。画面への反映は MainThread / runOnUiThread へ。 */
fun runBackground(name: String, block: () -> Unit) { thread(name = name, block = block) }
