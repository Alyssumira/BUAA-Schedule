package com.buaa.schedule.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

/**
 * 空状态卡（审查①V-04）：图标 + 标题 + 说明 + 动作。
 *
 * 首启那处本来就是一块玻璃卡，周视图与日视图那两处却只有裸文字——
 * "有课表但这周空着"和"完全没课"两种状态下的界面质量差一个档次。
 * 周视图那四个字尤其别扭：空白网格中央飘着一行小字，真的会被当成渲染坏了
 * （原处注释里就记着这份担心，只是最后只用了文字去缓解）。
 *
 * 外壳走 [GlassSurface] 的 PANEL 档，与其它面板同一质感；
 * 动作用 [actions] 传进来，调用方自己决定是 [Button] 还是别的。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    GlassSurface(
        variant = GlassVariant.PANEL,
        contentPadding = DesignTokens.spaceXL,
        shape = RoundedCornerShape(DesignTokens.cornerPage),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(DesignTokens.iconHero),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            actions()
        }
    }
}
