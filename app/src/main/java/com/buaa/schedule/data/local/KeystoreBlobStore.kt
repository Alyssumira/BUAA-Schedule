package com.buaa.schedule.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 一份凭据的加密落盘：AndroidKeyStore 的 AES-256/GCM，密钥不出安全硬件，
 * 存储格式 `Base64(iv):Base64(密文)` 写进 SharedPreferences 的单个键。
 *
 * 教务 Cookie（[BuaaCookieStore]）与 SPOC token（[SpocTokenStore]）各持一个实例，
 * prefs 名与密钥 alias 都不同：清一边不会把另一边的登录一起带掉。
 * **已落盘数据的读法就在这里** —— 改格式等于让老凭据全部解不开，要改先想清楚迁移。
 *
 * 任何一步失败都静默降级（[load] 返回 null）：密钥被重置、密文被篡改、
 * prefs 里躺着一段垃圾串，后果都只是"重新登录一次"，不崩、也不返回解密的乱码。
 */
internal class KeystoreBlobStore(
    private val prefsName: String,
    private val blobKey: String,
    private val keyAlias: String,
) {

    /** 空白载荷按"清掉"处理，而不是存一份空凭据：调用方的语义就是退登 */
    fun save(context: Context, payload: String) {
        if (payload.isBlank()) {
            clear(context)
            return
        }
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipher.doFinal(payload.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            prefs(context).edit { putString(blobKey, blob) }
        }
    }

    fun load(context: Context): String? = runCatching {
        val blob = prefs(context).getString(blobKey, null) ?: return null
        val parts = blob.split(":")
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val data = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrNull()

    /** 退出登录时必须调用：否则下次冷启动又把旧凭据注回去，看起来"退不掉" */
    fun clear(context: Context) {
        prefs(context).edit { remove(blobKey) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
