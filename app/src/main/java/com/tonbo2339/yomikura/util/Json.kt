package com.tonbo2339.yomikura

import org.json.JSONObject

/** JSON のオブジェクトとして読めなければ null (読めない文字列で落ちないように)。 */
fun String?.toJsonOrNull(): JSONObject? = this?.let { runCatching { JSONObject(it) }.getOrNull() }

/** JSON のオブジェクトとして読めなければ空のオブジェクト。 */
fun String?.toJsonOrEmpty(): JSONObject = toJsonOrNull() ?: JSONObject()
