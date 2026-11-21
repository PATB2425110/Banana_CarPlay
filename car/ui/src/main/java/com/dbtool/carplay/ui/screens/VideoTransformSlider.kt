package com.dbtool.carplay.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dbtool.carplay.ui.theme.CarColors
import com.dbtool.carplay.ui.theme.CarTheme

/** Generic video transform control extracted from the deleted instrument-theme UI. */
@Composable
internal fun DialSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit, colors: CarColors,
    format: (Float) -> String = { "%.0f px".format(it) },
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = CarTheme.type.bodySmall, color = colors.textSecondary, modifier = Modifier.width(72.dp))
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange,
            valueRange = range, onValueChangeFinished = onValueChangeFinished, modifier = Modifier.weight(1f))
        Text(format(value), style = CarTheme.type.bodySmall, color = colors.textSecondary, modifier = Modifier.width(72.dp))
    }
}
