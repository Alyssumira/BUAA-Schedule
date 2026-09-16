package com.buaa.schedule.reminder

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ROM 归属 → 实况产品名的映射。
 *
 * 这一格只决定用户看到"超级岛"还是"流体云"，但看错品牌的代价很实际：
 * 在 ColorOS 上许诺澎湃的行为、或在原生 Android 上让人去找一个不存在的岛，
 * 都是把没验证过的东西当成事实。映射本身是纯函数，`Build` 全部由参数代入。
 */
class LiveIslandSurfaceTest {

    private fun surface(
        brand: String?,
        manufacturer: String? = null,
        display: String? = null,
        colorOsProp: String? = null,
    ) = IslandDiagnostics.liveIslandSurfaceOf(brand, manufacturer, display, colorOsProp)

    @Test
    fun `ColorOS 系品牌都归到流体云`() {
        assertEquals(LiveIslandSurface.FLUID_CLOUD, surface("OPPO"))
        assertEquals(LiveIslandSurface.FLUID_CLOUD, surface("oneplus"))
        assertEquals(LiveIslandSurface.FLUID_CLOUD, surface("realme"))
        // 品牌串带空格与大小写差异是 ROM 常态
        assertEquals(LiveIslandSurface.FLUID_CLOUD, surface(" OPPO "))
    }

    @Test
    fun `没有 opporom 属性也认得出流体云，有属性时连品牌缺失也认得`() {
        assertEquals(LiveIslandSurface.FLUID_CLOUD, surface(null, colorOsProp = "V16.0.0.0"))
        assertEquals(LiveIslandSurface.FLUID_CLOUD, surface(null, display = "V14.0 ColorOS 14"))
    }

    @Test
    fun `小米系按品牌与 DISPLAY 双路识别`() {
        assertEquals(LiveIslandSurface.HYPER_ISLAND, surface("Xiaomi", "Xiaomi"))
        assertEquals(LiveIslandSurface.HYPER_ISLAND, surface("Redmi"))
        assertEquals(LiveIslandSurface.HYPER_ISLAND, surface("POCO"))
        // 品牌被改写过（部分海外 ROM）时，DISPLAY 是唯一线索
        assertEquals(LiveIslandSurface.HYPER_ISLAND, surface("SomeBrand", display = "OS03.0 HyperOS"))
        assertEquals(LiveIslandSurface.HYPER_ISLAND, surface("SomeBrand", display = "MIUI V14"))
    }

    @Test
    fun `认不出归属的机型不许诺任何一家的产品名`() {
        assertEquals(LiveIslandSurface.NATIVE, surface("google", "Google"))
        assertEquals(LiveIslandSurface.NATIVE, surface("HUAWEI"))
        assertEquals(LiveIslandSurface.NATIVE, surface(null, null, null, null))
        assertEquals(LiveIslandSurface.NATIVE, surface("", colorOsProp = ""))
    }
}
