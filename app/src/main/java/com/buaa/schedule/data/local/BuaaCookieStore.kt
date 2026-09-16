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
 * 教务会话 Cookie 的加密落盘。
 *
 * **为什么需要它**：WebView 的 `CookieManager` 只保证"进程存活期间"的会话。
 * Cookie 如果没有 `Max-Age` / `Expires`（SSO 的会话 Cookie 通常就是这种），
 * 它不会被写进磁盘 —— 用户从最近任务里划掉应用、或者被系统回收进程之后，
 * 再打开就只剩一个非会话 Cookie，访问 byxt 会被重定向回统一身份认证登录页，
 * 表现为「退出应用后登录状态还是没了」。
 *
 * 因此这里把 byxt / sso 两个域的 Cookie 单独加密存一份，冷启动时再注回 CookieManager。
 *
 * 加密：AndroidKeyStore 的 AES-256/GCM，密钥不出安全硬件；存储格式 `Base64(iv):Base64(密文)`。
 * 任何一步失败都静默降级（返回 null），用户最多重新登录一次，不会崩溃。
 */
object BuaaCookieStore {

    private const val PREFS = "buaa_cookie_store"
    private const val KEY_BLOB = "cookies"
    private const val KEY_ALIAS = "buaa_cookie_store_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

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
            prefs(context).edit { putString(KEY_BLOB, blob) }
        }
    }

    fun load(context: Context): String? = runCatching {
        val blob = prefs(context).getString(KEY_BLOB, null) ?: return null
        val parts = blob.split(":")
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val data = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrNull()

    /** 退出登录时必须调用：否则下次冷启动会把 Cookie 又注回去，看起来"退不掉" */
    fun clear(context: Context) {
        prefs(context).edit { remove(KEY_BLOB) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
