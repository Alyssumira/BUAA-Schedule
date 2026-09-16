package com.buaa.schedule.core.designsystem

/**
 * 玻璃表面数量治理：限制同一时刻最多渲染的 AGSL 玻璃表面数量，
 * 超过上限后降级为普通 tint 玻璃，避免低端机上大量卡片同时跑 blur/lens。
 */
object GlassRegistry {

    @Volatile private var activeGlassCount = 0

    /** 最大同时玻璃表面数；内存/性能治理用，后续可改成按 SoC 动态值 */
    const val MAX_GLASS_SURFACES = 32

    /** 尝试占用一个玻璃表面；返回 true 表示本次允许使用真玻璃 */
    @Synchronized
    fun acquire(): Boolean {
        if (activeGlassCount >= MAX_GLASS_SURFACES) return false
        activeGlassCount++
        return true
    }

    @Synchronized
    fun release() {
        if (activeGlassCount > 0) activeGlassCount--
    }

    val activeCount: Int get() = activeGlassCount
}