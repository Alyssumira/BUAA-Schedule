package com.buaa.schedule.data.local

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [BuaaCookieStore] 的往返与容错（需设备：依赖 AndroidKeyStore）。
 *
 * 这是**唯一的凭据持久化点**，此前零测试。契约是：任何一步失败都静默降级返回 null，
 * 用户最多重新登录一次，绝不抛异常、绝不返回被篡改后能解密出乱码的内容。
 */
@RunWith(AndroidJUnit4::class)
class BuaaCookieStoreTest {

    private lateinit var context: Context

    // 与 BuaaCookieStore 内部一致（包名/键名改动时这里会失败，正是想要的）
    private val prefs get() = context.getSharedPreferences("buaa_cookie_store", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        BuaaCookieStore.clear(context)
    }

    @After
    fun tearDown() {
        BuaaCookieStore.clear(context)
    }

    private val sample = "JSESSIONID=abc123; route=def456; sso_token=ghi789"

    @Test
    fun saveThenLoadRoundTrips() {
        BuaaCookieStore.save(context, sample)
        assertEquals(sample, BuaaCookieStore.load(context))
    }

    @Test
    fun unicodeAndSpecialCharactersSurvive() {
        val payload = "JSESSIONID=中文-值; x=a=b; y=\"quoted\"; z=1,2,3"
        BuaaCookieStore.save(context, payload)
        assertEquals(payload, BuaaCookieStore.load(context))
    }

    @Test
    fun repeatedSaveOverwritesPreviousValue() {
        BuaaCookieStore.save(context, "old=1")
        BuaaCookieStore.save(context, "new=2")
        assertEquals("new=2", BuaaCookieStore.load(context))
    }

    @Test
    fun clearRemovesPersistedCookie() {
        BuaaCookieStore.save(context, sample)
        BuaaCookieStore.clear(context)
        assertNull("退出登录后必须读不到 Cookie", BuaaCookieStore.load(context))
    }

    @Test
    fun blankPayloadClearsInsteadOfStoring() {
        BuaaCookieStore.save(context, sample)
        BuaaCookieStore.save(context, "   ")
        assertNull(BuaaCookieStore.load(context))
    }

    @Test
    fun tamperedCipherTextLoadsNullInsteadOfGarbage() {
        BuaaCookieStore.save(context, sample)
        val blob = prefs.getString("cookies", null) ?: error("未写入")
        val (ivPart, dataPart) = blob.split(":")
        val data = Base64.decode(dataPart, Base64.NO_WRAP)
        // 翻掉密文首个字节：GCM 校验必然失败
        data[0] = (data[0].toInt() xor 0xFF).toByte()
        val tampered = ivPart + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
        prefs.edit(commit = true) { putString("cookies", tampered) }

        assertNull("篡改后的凭据必须解密失败并返回 null", BuaaCookieStore.load(context))
    }

    @Test
    fun malformedBlobLoadsNull() {
        listOf(
            "not-a-valid-blob",
            "",
            "onlyonepart",
            "a:b:c",
            "!!!:!!!",
        ).forEach { malformed ->
            prefs.edit(commit = true) { putString("cookies", malformed) }
            assertNull("畸形存储 '$malformed' 必须返回 null", BuaaCookieStore.load(context))
        }
    }

    @Test
    fun persistedBlobIsNotPlaintext() {
        BuaaCookieStore.save(context, sample)
        val blob = prefs.getString("cookies", null) ?: error("未写入")
        assertEquals(false, blob.contains("JSESSIONID"))
        assertNotEquals(sample, blob)
    }
}
