package com.dbtool.carplay.api.display.theme

/** Shared application/map appearance setting, not the removed instrument skin system. */
enum class ClusterAppearanceMode {
    DARK, LIGHT, SYSTEM;
    fun resolveDark(systemDark: Boolean): Boolean = when (this) {
        DARK -> true
        LIGHT -> false
        SYSTEM -> systemDark
    }
    val displayName: String get() = when (this) {
        DARK -> "深色"
        LIGHT -> "浅色"
        SYSTEM -> "跟随系统"
    }
    companion object {
        fun parse(raw: String?): ClusterAppearanceMode? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}
