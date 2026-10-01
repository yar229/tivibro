package com.tvibro.ui.theme

import androidx.appcompat.app.AppCompatDelegate

/**
 * Maps the "Color theme" setting onto the AppCompat night mode.
 *
 * "system" is not a palette of its own: it resolves through the `-night` resource qualifier, so the
 * app follows whatever the device reports. That is why the dark palette lives in `values-night`
 * and the light one in plain `values`.
 */
object ThemeMode {

    const val SYSTEM = "system"
    const val DARK = "dark"
    const val LIGHT = "light"

    /** The values the setting can hold, in the order they are offered to the user. */
    val VALUES = listOf(SYSTEM, DARK, LIGHT)

    fun nightMode(value: String?): Int = when (value) {
        DARK -> AppCompatDelegate.MODE_NIGHT_YES
        LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /**
     * Applies the stored value process-wide. Calling it before the first activity is created
     * keeps a wrong theme from flashing on launch; calling it later recreates the activities that
     * are already on screen.
     */
    fun apply(value: String?) {
        val mode = nightMode(value)
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}