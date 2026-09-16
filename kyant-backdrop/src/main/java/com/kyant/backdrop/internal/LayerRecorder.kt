/*
   Ported to a plain Android library for BUAA-Schedule.
   Upstream: Kyant0/AndroidLiquidGlass 2.0.0 (with SleepDown-Schedule patches), Apache-2.0.
   On Compose 1.11 this mirrors upstream: GraphicsLayer.record(size) carries the
   shared-canvas swap semantics that let outer-scope drawContent() be captured.
*/
package com.kyant.backdrop.internal

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize

internal fun DrawScope.recordLayer(
    node: DelegatableNode,
    layer: GraphicsLayer,
    size: IntSize = this.size.toIntSize(),
    block: DrawScope.() -> Unit
) {
    val density = node.requireDensity()
    layer.record(size) {
        val prevDensity = drawContext.density
        drawContext.density = density
        try {
            this.block()
        } finally {
            drawContext.density = prevDensity
        }
    }
}
