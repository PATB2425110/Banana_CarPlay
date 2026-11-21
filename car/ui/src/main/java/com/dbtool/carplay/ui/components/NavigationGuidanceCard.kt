package com.dbtool.carplay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dbtool.carplay.api.navigation.CarNavigationSnapshot
import com.dbtool.carplay.ui.screens.navigationArrivalText
import com.dbtool.carplay.ui.screens.navigationDestinationText
import com.dbtool.carplay.ui.screens.navigationDistanceText
import com.dbtool.carplay.ui.screens.navigationDurationText
import com.dbtool.carplay.ui.screens.navigationHeadline
import com.dbtool.carplay.ui.theme.CarTheme
import java.time.ZoneId

/** Local DBPlay presentation of the iPhone's current CarPlay route guidance. */
@Composable
fun NavigationGuidanceCard(
    snapshot: CarNavigationSnapshot,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    val dimens = CarTheme.dimens
    val colors = CarTheme.colors
    val rows = listOfNotNull(
        navigationDestinationText(snapshot)?.let { NavigationCardRow("目的地", it) },
        navigationDistanceText(snapshot.segmentMeters)?.let { NavigationCardRow("下一个转向", it) },
        navigationDistanceText(snapshot.routeMeters)?.let { NavigationCardRow("剩余路程", it) },
        navigationDurationText(snapshot.routeSeconds)?.let { NavigationCardRow("预计剩余", it) },
        navigationArrivalText(snapshot.arrivalEpochSeconds, zoneId)?.let { NavigationCardRow("预计到达", it) },
    )

    CarCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(dimens.cardPadding),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceM)) {
            Text(
                text = "下一步",
                style = CarTheme.type.label,
                color = colors.textSecondary,
            )
            Text(
                text = navigationHeadline(snapshot),
                style = CarTheme.type.titleLarge,
                color = colors.textPrimary,
            )
            rows.forEachIndexed { index, row ->
                if (index > 0) CarRowDivider()
                CarSettingInfo(title = row.title, value = row.value)
            }
        }
    }
}

private data class NavigationCardRow(val title: String, val value: String)
