package com.dbtool.carplay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dbtool.carplay.ui.CarUiModel
import com.dbtool.carplay.ui.components.CarCard
import com.dbtool.carplay.ui.components.CarScreen
import com.dbtool.carplay.ui.components.NavigationGuidanceCard
import com.dbtool.carplay.ui.components.SectionHeader
import com.dbtool.carplay.ui.theme.CarTheme

/** Local read-only view of the active iPhone route; it never overlays the CarPlay video surface. */
@Composable
fun NavigationScreen(
    model: CarUiModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CarScreen(
        title = "导航",
        subtitle = "iPhone CarPlay 导航信息",
        onBack = onBack,
        modifier = modifier,
    ) {
        val snapshot = model.navigation
        if (snapshot == null) {
            SectionHeader(title = "等待导航")
            CarCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(CarTheme.dimens.spaceS)) {
                    Text(
                        text = "暂无导航信息",
                        style = CarTheme.type.titleMedium,
                        color = CarTheme.colors.textPrimary,
                    )
                    Text(
                        text = "连接 iPhone 并开始导航后，这里会显示转向和剩余路程。",
                        style = CarTheme.type.bodyMedium,
                        color = CarTheme.colors.textSecondary,
                    )
                }
            }
        } else {
            SectionHeader(title = "当前路线")
            NavigationGuidanceCard(snapshot = snapshot)
        }
    }
}
