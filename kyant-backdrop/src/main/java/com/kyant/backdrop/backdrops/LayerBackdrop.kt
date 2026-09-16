package com.kyant.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.internal.InverseLayerScope

private val DefaultOnDraw: ContentDrawScope.() -> Unit = { drawContent() }

/**
 * BUAA addition: manual-recording backdrop. The layer is filled by the caller
 * (e.g. GraphicsLayer.record inside drawWithCache), not by a producer node —
 * Compose 1.7's record block cannot capture outer-scope drawContent().
 * Attach [layerBackdropCoordinates] to the node that owns the recording so
 * consumers can resolve their relative offset.
 */
@Composable
fun rememberManualLayerBackdrop(graphicsLayer: GraphicsLayer): LayerBackdrop {
    return remember(graphicsLayer) {
        LayerBackdrop(graphicsLayer, onDraw = {})
    }
}

/** BUAA addition: reports the manual recorder's layout coordinates to the backdrop. */
fun Modifier.layerBackdropCoordinates(backdrop: LayerBackdrop): Modifier =
    this.then(LayerBackdropCoordinatesElement(backdrop))

private class LayerBackdropCoordinatesElement(
    val backdrop: LayerBackdrop
) : ModifierNodeElement<LayerBackdropCoordinatesNode>() {

    override fun create(): LayerBackdropCoordinatesNode = LayerBackdropCoordinatesNode(backdrop)

    override fun update(node: LayerBackdropCoordinatesNode) {
        if (node.backdrop !== backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
        }
    }

    override fun equals(other: Any?): Boolean = other is LayerBackdropCoordinatesElement && other.backdrop === backdrop

    override fun hashCode(): Int = backdrop.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdropCoordinates"
    }
}

private class LayerBackdropCoordinatesNode(
    var backdrop: LayerBackdrop
) : GlobalPositionAwareModifierNode, Modifier.Node() {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.layerCoordinates = coordinates
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
    }
}

@Composable
fun rememberLayerBackdrop(
    graphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    onDraw: ContentDrawScope.() -> Unit = DefaultOnDraw
): LayerBackdrop {
    return remember(graphicsLayer, onDraw) {
        LayerBackdrop(graphicsLayer, onDraw)
    }
}

@Stable
class LayerBackdrop internal constructor(
    val graphicsLayer: GraphicsLayer,
    internal val onDraw: ContentDrawScope.() -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)

    private var inverseLayerScope: InverseLayerScope? = null

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val coordinates = coordinates ?: return
        val layerCoordinates = layerCoordinates ?: return
        withTransform({
            if (layerBlock != null) {
                with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
            }
            val offset =
                try {
                    layerCoordinates.localPositionOf(coordinates)
                } catch (_: Exception) {
                    // TODO: outer transformations lead to wrong position calculation
                    coordinates.positionInWindow() - layerCoordinates.positionInWindow()
                }
            translate(-offset.x, -offset.y)
        }) {
            drawLayer(graphicsLayer)
        }
    }

    private fun obtainInverseLayerScope(): InverseLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseLayerScope().also { inverseLayerScope = it }
    }
}

