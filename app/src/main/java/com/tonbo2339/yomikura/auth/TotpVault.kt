package com.tonbo2339.yomikura

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 認証アプリの秘密鍵の保管庫。Android Keystore の AES-GCM 鍵 (取り出せない) で暗号化して、専用の SharedPreferences に置く。
 * 保存データの JSON (NHData) には入れない = PC バックアップ・設定の写し・自動バックアップに入らない (バックアップの対象は backup_rules.xml / data_extraction_rules.xml で決まる)。
 * 機種変更や入れ直しで Keystore の鍵が無くなったら、復号できず「未登録」に戻る (なろうの設定で鍵を引き直して登録し直す)。
 * 鍵の中身はログにも画面にも出さない。
 */
class TotpVault(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 登録済みか (復号できる鍵が入っているか)。 */
    fun has(): Boolean = read() != null

    /** 鍵 (Base32) を登録する。読めない鍵なら false。 */
    fun save(raw: String?): Boolean {
        val key = Totp.normalize(raw) ?: return false
        return try {
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, aesKey()) }
            val blob = c.iv + c.doFinal(key.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(ENTRY, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
        } catch (e: Exception) {
            false
        }
    }

    /** 鍵 (Base32)。Kotlin の中だけで使う (PC バックアップに入れるとき)。JS には返さない・ログに出さない。未登録なら null */
    fun secret(): String? = read()

    fun clear() {
        prefs.edit().remove(ENTRY).apply()
    }

    /** いまのコード。未登録なら null。 */
    fun code(nowMs: Long = System.currentTimeMillis()): String? = read()?.let { Totp.code(it, nowMs / 1000) }

    private fun read(): String? {
        val blob = prefs.getString(ENTRY, null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() } ?: return null
        if (blob.size <= IV_BYTES) return null
        return try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            String(c.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    // 保管庫は使う側ごとに作られる (窓口など) ので、初めて鍵を作るときに 2 か所が重なると、あとから作った鍵が先の鍵を上書きして、先に暗号化した鍵が読めなくなる。作るところだけ 1 つずつにする
    private fun aesKey(): SecretKey = synchronized(LOCK) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        gen.generateKey()
    }

    private companion object {
        val LOCK = Any()
        const val PREFS = "nh_totp"
        const val ENTRY = "secret"
        const val ALIAS = "nh_totp_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
