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
 * [IClassIdStore] 的往返与**两边隔离**（需设备：依赖 AndroidKeyStore，JVM 单测跑不到）。
 *
 * 加密实现早已收口到 [KeystoreBlobStore]，所以这一族唯一的新风险还是那一个：
 * 两份存储（教务 Cookie / iClass id）的 prefs 名与密钥 alias 有没有写成同一份。
 * 写错不会报任何错，只会让"退出 iClass 顺手把教务登录也登出了"，
 * 或者反过来：清教务 Cookie 的人发现自己签不上课了。
 * （T84 之前这一族是**三份**：智学北航的 token 存储也在这张表里，T85 连着那一族一起拆了。
 * 隔离这条不变量的形状没变，只是少了一边 —— 剩下的两边仍然一边都不许省。）
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
    private val cookiePrefs get() = context.getSharedPreferences("buaa_cookie_store", Context.MODE_PRIVATE)

    /** 形状照实测：`result.id` 是一串数字 */
    private val sample = "2488752"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        clearBoth()
    }

    @After
    fun tearDown() {
        clearBoth()
    }

    private fun clearBoth() {
        IClassIdStore.clear(context)
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
    fun clearingIClassLeavesTheCookieStoreAlone() {
        IClassIdStore.save(context, sample)
        BuaaCookieStore.save(context, "JSESSIONID=abc")

        IClassIdStore.clear(context)

        assertNull("iClass 退出后必须读不到 id", IClassIdStore.load(context))
        assertEquals("清 iClass 不该连带清掉教务 Cookie", "JSESSIONID=abc", BuaaCookieStore.load(context))
    }

    @Test
    fun clearingTheCookieStoreLeavesIClassAlone() {
        IClassIdStore.save(context, sample)
        BuaaCookieStore.save(context, "JSESSIONID=abc")

        BuaaCookieStore.clear(context)

        assertEquals("清教务 Cookie 不该把 iClass 的登录带掉", sample, IClassIdStore.load(context))
    }

    @Test
    fun theTwoStoresDoNotShareAPrefsFile() {
        IClassIdStore.save(context, sample)
        // 同一枚键躺在对方的 prefs 里 = 两份里有一份的 prefs 名或键名写重了，收口时最容易写错的一处
        assertNull(cookiePrefs.getString("credential", null))
        IClassIdStore.clear(context)
        BuaaCookieStore.save(context, "JSESSIONID=abc")
        assertNull(iClassPrefs.getString("credential", null))
    }
}
