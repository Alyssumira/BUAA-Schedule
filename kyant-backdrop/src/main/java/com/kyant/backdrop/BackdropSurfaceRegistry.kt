package com.kyant.backdrop

/**
 * Backdrop 运行时表面治理：记录当前活跃的 DrawBackdrop 节点数量，
 * 用于调试/观测以及未来按数量自动降级。
 */
object BackdropSurfaceRegistry {

    @Volatile private var activeSurfaces = 0

    const val MAX_SURFACES = 64

    @Synchronized
    fun acquire(): Boolean {
        if (activeSurfaces >= MAX_SURFACES) return false
        activeSurfaces++
        return true
    }

    @Synchronized
    fun release() {
        if (activeSurfaces > 0) activeSurfaces--
    }

    val activeCount: Int get() = activeSurfaces
}