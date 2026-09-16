package com.buaa.schedule

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buaa.schedule.widget.WidgetAppearance
import com.buaa.schedule.widget.WidgetAppearanceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 组件外观的实例级持久化测试。
 *
 * 每个桌面组件实例必须互不影响（同一份 prefs 里按 appWidgetId 分区），
 * 且实例被移除后配置要被清掉。
 */
@RunWith(AndroidJUnit4::class)
class WidgetAppearanceStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun unconfiguredInstanceFallsBackToDefaults() {
        val id = 90001
        WidgetAppearanceStore.remove(context, intArrayOf(id))
        assertFalse(WidgetAppearanceStore.isConfigured(context, id))
        assertEquals(WidgetAppearance(), WidgetAppearanceStore.load(context, id))
    }

    @Test
    fun saveThenLoadRoundTrips() {
        val id = 90002
        val custom = WidgetAppearance(
            backgroundColor = 0xFF5B4BD6.toInt(),
            alphaPercent = 35,
            cornerBucket = 5,
            textMode = WidgetAppearance.TEXT_DARK,
            showTitle = false,
        )
        WidgetAppearanceStore.save(context, id, custom)
        assertTrue(WidgetAppearanceStore.isConfigured(context, id))
        assertEquals(custom, WidgetAppearanceStore.load(context, id))
    }

    @Test
    fun instancesAreIsolatedFromEachOther() {
        val first = 90003
        val second = 90004
        WidgetAppearanceStore.remove(context, intArrayOf(first, second))

        WidgetAppearanceStore.save(
            context,
            first,
            WidgetAppearance(backgroundColor = 0xFF1A73E8.toInt(), alphaPercent = 100),
        )
        // 第二个实例没配置过，必须仍是默认外观
        assertEquals(WidgetAppearance(), WidgetAppearanceStore.load(context, second))
        assertEquals(
            0xFF1A73E8.toInt(),
            WidgetAppearanceStore.load(context, first).backgroundColor,
        )
    }

    @Test
    fun resetRestoresDefaults() {
        val id = 90005
        WidgetAppearanceStore.save(context, id, WidgetAppearance(alphaPercent = 10))
        val reset = WidgetAppearanceStore.reset(context, id)
        assertEquals(WidgetAppearance(), reset)
        assertFalse(WidgetAppearanceStore.isConfigured(context, id))
    }

    @Test
    fun removeClearsConfigurationOfDeletedInstances() {
        val id = 90006
        WidgetAppearanceStore.save(context, id, WidgetAppearance(alphaPercent = 20))
        assertTrue(WidgetAppearanceStore.isConfigured(context, id))
        WidgetAppearanceStore.remove(context, intArrayOf(id))
        assertFalse(WidgetAppearanceStore.isConfigured(context, id))
        assertEquals(WidgetAppearance(), WidgetAppearanceStore.load(context, id))
    }

    @Test
    fun alphaOutOfRangeIsStoredClamped() {
        val id = 90007
        // 防御性：即便有人塞了越界值，读回来也必须在 0..100
        WidgetAppearanceStore.save(context, id, WidgetAppearance(alphaPercent = 500))
        val loaded = WidgetAppearanceStore.load(context, id)
        assertTrue("不透明度读回后必须落在 0..100", loaded.alphaPercent in 0..100)
    }
}
