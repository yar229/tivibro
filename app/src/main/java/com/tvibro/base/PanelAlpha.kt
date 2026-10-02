package com.tvibro.base

import android.content.Context
import android.view.View
import com.tvibro.TvBroApp
import kotlin.math.roundToInt

/**
 * Dims a panel's own background down to the opacity chosen in the appearance settings.
 *
 * The setting is a percentage in which 100 means the shipped look, so it is applied as a
 * multiplier on top of the alpha the panel drawable already carries. At 100 a panel stays exactly
 * as it was drawn; lower values only take opacity away, they never add any.
 */
fun View.applyPanelTransparency(context: Context) {
    val percent = TvBroApp.prefs(context).uiTransparency.coerceIn(0, 100)
    // mutate() is not optional here: drawables loaded from resources share a ConstantState, so
    // writing the alpha without taking a private copy first would also dim every other view that
    // happens to use the same drawable.
    background?.mutate()?.alpha = (percent / 100f * 255f).roundToInt()
}