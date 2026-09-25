package com.buaa.schedule.data.local

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [IClassIdStore] 的往返与**三边隔离**（需设备：依赖 AndroidKeyStore，JVM 单测跑不到）。
 *
 * 加密实现早已收口到 [KeystoreBlobStore]，所以这一族唯一的新风险还是那一个：
 * 三份存储（教务 Cookie / SPOC token / iClass id）的 prefs 名与密钥 alias 有没有写成同一份。
 * 写错不会报任何错，只会让"退出 iClass 顺手把智学北航也登出了"，
 * 或者更糟：清教务 Cookie 的人发现自己签不上课了。
 *
 * 另两条是这一族自己的契约：
 * - 落盘的密文里不许出现明文 id（`persistedBlobIsNotPlaintext`）；
 * - 空白载荷按"清掉"处理（`blankPayloadClearsInsteadOfStoringEmptyId`）——
 *   存了一枚空 id 的话，`hasSession()` 会一路答"是"，而每次签到都拼出 `&id=` 空值。
 */
@RunWith(AndroidJUnit4::class)
class IClassIdStoreTest {

    private lateinit var context: Context

    private val iClassPrefs get() = context.getSharedPreferences("iclass_id_store", Context.MODE_PRIVATE)
    private val spocPrefs get() = context.getSharedPreferences("spoc_token_store", Context.MODE_PRIVATE)
    private val cookiePrefs get() = context.getSharedPreferences("buaa_cookie_store", Context.MODE_PRIVATE)

    /** 形状照实测：`result.id` 是一串数字 */
    private val sample = "2488752"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        clearAllThree()
    }

    @After
    fun tearDown() {
        clearAllThree()
    }

    private fun clearAllThree() {
        IClassIdStore.clear(context)
        SpocTokenStore.clear(context)
        BuaaCookieStore.clear(context)
    }

    @Test
    fun saveThenLoadRoundTrips() {
        IClassIdStore.save(context, sample)
        assertEquals(sample, IClassIdStore.load(context))
    }

    @Test
    fun persistedBlobIsNotPlaintext() {
        IClassIdStore.save(context, sample)
        val blob = iClassPrefs.getString("credential", null) ?: error("未写入")
        assertEquals(false, blob.contains(sample))
    }

    @Test
    fun blankPayloadClearsInsteadOfStoringEmptyId() {
        IClassIdStore.save(context, sample)
        IClassIdStore.save(context, "   ")
        assertNull("空白载荷必须当成退登，而不是存下一枚空 id", IClassIdStore.load(context))
    }

    @Test
    fun clearingIClassLeavesTheOtherTwoAlone() {
        IClassIdStore.save(context, sample)
        SpocTokenStore.save(context, """{"token":"Inco-eyJhbGciOi"}""")
        BuaaCookieStore.save(context, "JSESSIONID=abc")

        IClassIdStore.clear(context)

        assertNull("iClass 退出后必须读不到 id", IClassIdStore.load(context))
        assertEquals("清 iClass 不该连带清掉 SPOC", """{"token":"Inco-eyJhbGciOi"}""", SpocTokenStore.load(context))
        assertEquals("清 iClass 不该连带清掉教务 Cookie", "JSESSIONID=abc", BuaaCookieStore.load(context))
    }

    @Test
    fun clearingTheOtherTwoLeavesIClassAlone() {
        IClassIdStore.save(context, sample)
        SpocTokenStore.save(context, """{"token":"Inco-eyJhbGciOi"}""")
        BuaaCookieStore.save(context, "JSESSIONID=abc")

        SpocTokenStore.clear(context)
        BuaaCookieStore.clear(context)

        assertEquals("清 SPOC / 教务都不该把 iClass 的登录带掉", sample, IClassIdStore.load(context))
    }

    @Test
    fun theThreeStoresDoNotShareAPrefsFile() {
        IClassIdStore.save(context, sample)
        // 同一枚键躺在对方的 prefs 里 = 三份里有一份的 prefs 名或键名写重了，收口时最容易写错的一处
        assertNull(spocPrefs.getString("credential", null))
        assertNull(cookiePrefs.getString("credential", null))
        IClassIdStore.clear(context)
        SpocTokenStore.save(context, sample)
        assertNull(iClassPrefs.getString("credential", null))
    }
}
