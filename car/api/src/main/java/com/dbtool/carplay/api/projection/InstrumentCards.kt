package com.dbtool.carplay.api.projection

import android.content.Context
import com.dbtool.carplay.api.media.CarNowPlaying
import com.dbtool.carplay.api.navigation.CarNavigationSnapshot

/** Independent instrument overlays. No theme, dial layout, or homepage-card dependency. */
data class InstrumentCardSettings(
    val navigationEnabled: Boolean = false,
    val musicEnabled: Boolean = false,
    val navigationX: Float = 80f,
    val navigationY: Float = 80f,
    val musicX: Float = 80f,
    val musicY: Float = 500f,
) {
    fun bounded() = copy(
        navigationX = navigationX.safe(1490f, 80f), navigationY = navigationY.safe(540f, 80f),
        musicX = musicX.safe(1300f, 80f), musicY = musicY.safe(620f, 500f),
    )
    private fun Float.safe(max: Float, fallback: Float) = if (isFinite()) coerceIn(0f, max) else fallback
}

object InstrumentCards {
    @Volatile private var navigation: () -> CarNavigationSnapshot? = { null }
    @Volatile private var music: () -> CarNowPlaying? = { null }
    fun configure(navigation: () -> CarNavigationSnapshot?, music: () -> CarNowPlaying?) {
        this.navigation = navigation
        this.music = music
    }
    fun navigation(): CarNavigationSnapshot? = runCatching { navigation.invoke() }.getOrNull()
    fun music(): CarNowPlaying? = runCatching { music.invoke() }.getOrNull()
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("instrument_cards", Context.MODE_PRIVATE)
    fun load(context: Context): InstrumentCardSettings = prefs(context).let {
        InstrumentCardSettings(false, it.getBoolean("music", false),
            it.getFloat("navigation_x", 80f), it.getFloat("navigation_y", 80f),
            it.getFloat("music_x", 80f), it.getFloat("music_y", 500f)).bounded()
    }
    fun save(context: Context, value: InstrumentCardSettings) {
        val v = value.bounded()
        prefs(context).edit().putBoolean("navigation", v.navigationEnabled).putBoolean("music", v.musicEnabled)
            .putFloat("navigation_x", v.navigationX).putFloat("navigation_y", v.navigationY)
            .putFloat("music_x", v.musicX).putFloat("music_y", v.musicY).apply()
    }
}
