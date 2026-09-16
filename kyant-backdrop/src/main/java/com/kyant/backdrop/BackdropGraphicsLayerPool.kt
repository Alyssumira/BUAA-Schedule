package com.kyant.backdrop

import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer
import java.util.ArrayDeque

/**
 * Backdrop 采样层 GraphicsLayer 池。
 *
 * 创建/释放 GraphicsLayer 是有代价的；这里把已释放的层回收复用，
 * 减少低端机上频繁进出组合时的图层分配/销毁。
 */
object BackdropGraphicsLayerPool {

    private const val MAX_POOL_SIZE = 16
    private val pool = ArrayDeque<GraphicsLayer>()

    @Synchronized
    fun acquire(context: GraphicsContext): GraphicsLayer = pool.pollLast() ?: context.createGraphicsLayer()

    @Synchronized
    fun release(context: GraphicsContext, layer: GraphicsLayer) {
        if (pool.size < MAX_POOL_SIZE) {
            pool.addLast(layer)
        } else {
            context.releaseGraphicsLayer(layer)
        }
    }

    @Synchronized
    fun clear(context: GraphicsContext) {
        while (pool.isNotEmpty()) context.releaseGraphicsLayer(pool.removeLast())
    }
}