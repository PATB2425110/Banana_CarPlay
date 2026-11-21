package com.dbtool.carplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun FpsOverlay(fps: Float, modifier: Modifier = Modifier): Unit = Box(
    modifier = modifier
        .background(Color.Black.copy(alpha = .62f))
        .padding(horizontal = 7.dp, vertical = 4.dp),
) {
    Text("FPS ${"%.1f".format(java.util.Locale.US, fps)}", color = Color.White)
}
