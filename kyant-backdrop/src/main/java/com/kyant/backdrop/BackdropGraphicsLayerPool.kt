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

    /**
     * 允许进池的层的最大面积（像素）。
     *
     * 一个 `GraphicsLayer` 释放回池后仍按**最后一次使用的尺寸**保留后备纹理：
     * 整屏背景层（1080×2400 ≈ 2.6M 像素 ≈ 10MB RGBA）和一张几十像素见方的卡片层
     * 在"个数"这个口径下是等价的，池顶 16 个全是大层时就是上百 MB 的常驻显存。
     * 池子存在的意义是省掉"频繁进出组合的小层"的分配开销（课程卡、玻璃表面），
     * 大层的分配频率本来就低，留着不划算 —— 超阈值直接还给 `GraphicsContext`。
     */
    private const val MAX_RETAINED_AREA_PX = 1_300_000

    private val pool = ArrayDeque<GraphicsLayer>()

    @Synchronized
    fun acquire(context: GraphicsContext): GraphicsLayer = pool.pollLast() ?: context.createGraphicsLayer()

    @Synchronized
    fun release(context: GraphicsContext, layer: GraphicsLayer) {
        val size = layer.size
        val tooBig = size.width.toLong() * size.height.toLong() > MAX_RETAINED_AREA_PX
        if (!tooBig && pool.size < MAX_POOL_SIZE) {
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