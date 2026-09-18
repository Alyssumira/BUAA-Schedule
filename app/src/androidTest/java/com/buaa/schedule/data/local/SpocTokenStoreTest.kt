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
 * [SpocTokenStore] 的往返与**两边隔离**（需设备：依赖 AndroidKeyStore）。
 *
 * 加密实现收口到 [KeystoreBlobStore] 之后，剩下的风险只有一个：
 * 两个实例的 prefs 名 / 键名 / 密钥 alias 有没有写成同一份。
 * 写错了不会有任何报错，只会让"退出教务登录顺手把 SPOC 也登出了"——
 * 所以这条契约测试比往返测试更值得留。
 */
@RunWith(AndroidJUnit4::class)
class SpocTokenStoreTest {

    private lateinit var context: Context

    private val spocPrefs get() = context.getSharedPreferences("spoc_token_store", Context.MODE_PRIVATE)
    private val cookiePrefs get() = context.getSharedPreferences("buaa_cookie_store", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // 两边一起清：下面的隔离用例会往教务那份 prefs 里写东西
        SpocTokenStore.clear(context)
        BuaaCookieStore.clear(context)
    }

    @After
    fun tearDown() {
        SpocTokenStore.clear(context)
        BuaaCookieStore.clear(context)
    }

    private val sample = """{"token":"Inco-eyJhbGciOi","user":"E12345678"}"""

    @Test
    fun saveThenLoadRoundTrips() {
        SpocTokenStore.save(context, sample)
        assertEquals(sample, SpocTokenStore.load(context))
    }

    @Test
    fun persistedBlobIsNotPlaintext() {
        SpocTokenStore.save(context, sample)
        val blob = spocPrefs.getString("credential", null) ?: error("未写入")
        assertEquals(false, blob.contains("Inco-"))
    }

    @Test
    fun clearingSpocLeavesBuaaCookieAlone() {
        SpocTokenStore.save(context, sample)
        BuaaCookieStore.save(context, "JSESSIONID=abc")
        SpocTokenStore.clear(context)

        assertNull("SPOC 退出后必须读不到 token", SpocTokenStore.load(context))
        assertEquals("清 SPOC 不该连带清掉教务会话", "JSESSIONID=abc", BuaaCookieStore.load(context))
    }

    @Test
    fun clearingBuaaLeavesSpocAlone() {
        SpocTokenStore.save(context, sample)
        BuaaCookieStore.save(context, "JSESSIONID=abc")
        BuaaCookieStore.clear(context)

        assertNull("教务退出后必须读不到 Cookie", BuaaCookieStore.load(context))
        assertEquals("清教务不该连带把 SPOC 登出", sample, SpocTokenStore.load(context))
    }

    @Test
    fun theTwoStoresDoNotShareAPrefsFile() {
        SpocTokenStore.save(context, sample)
        // 同一份密文躺在对方的 prefs 里 = 两边共用了键名或 prefs 名，收口时最容易写错的一处
        assertNull(cookiePrefs.getString("credential", null))
        SpocTokenStore.clear(context)
        BuaaCookieStore.save(context, "JSESSIONID=abc")
        assertNull(spocPrefs.getString("cookies", null))
    }
}
