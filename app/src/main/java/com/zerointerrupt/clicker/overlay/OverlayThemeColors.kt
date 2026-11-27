package com.zerointerrupt.clicker.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import com.google.android.material.R as MaterialR
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.zerointerrupt.clicker.R
import com.zerointerrupt.clicker.model.ClickConfigStore
import com.zerointerrupt.clicker.model.ClickGlobalSettings
import com.zerointerrupt.clicker.ui.theme.tuneArgbColor

/**
 * Resolves Material You (Dynamic Color on Android 12+) / MD3 DayNight color tokens
 * for WindowManager overlay views so floating controls harmonize with the main Compose UI.
 */
data class OverlayThemeColors(
    val themedContext: Context,
    val isDark: Boolean,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outlineVariant: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val error: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val activeRunningGreen: Int,
    val activeRunningGreenContainer: Int,
    val ripple: Int
) {
    fun createRippleDrawable(
        content: Drawable?,
        maskCornerRadii: FloatArray? = null,
        isOvalMask: Boolean = false
    ): RippleDrawable {
        val mask = if (content == null) {
            GradientDrawable().apply {
                shape = if (isOvalMask) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                if (!isOvalMask && maskCornerRadii != null) {
                    cornerRadii = maskCornerRadii
                }
            }
        } else {
            null
        }
        return RippleDrawable(ColorStateList.valueOf(ripple), content, mask)
    }

    companion object {
        fun resolve(baseContext: Context): OverlayThemeColors {
            val settings = ClickConfigStore.loadSettings(baseContext)
            val baseUiMode = baseContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val isDark = when (settings.themeMode) {
                ClickGlobalSettings.THEME_DARK -> true
                ClickGlobalSettings.THEME_LIGHT -> false
                else -> baseUiMode == Configuration.UI_MODE_NIGHT_YES
            }

            val overrideConfig = Configuration(baseContext.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val themedBase = baseContext.createConfigurationContext(overrideConfig)
            val styled = ContextThemeWrapper(themedBase, R.style.Theme_ZeroInterruptClicker)
            val dynamic = if (settings.dynamicColor) {
                DynamicColors.wrapContextIfAvailable(styled)
            } else {
                styled
            }

            fun attr(attrId: Int, fallbackDark: Int, fallbackLight: Int): Int {
                val fb = if (isDark) fallbackDark else fallbackLight
                return MaterialColors.getColor(dynamic, attrId, fb)
            }

            val sat = ClickGlobalSettings.BASE_COLOR_SATURATION_OFFSET + settings.colorSaturationBoost
            val lit = ClickGlobalSettings.BASE_COLOR_LIGHTNESS_OFFSET + settings.colorLightnessBoost
            fun tuneAccent(c: Int): Int = tuneArgbColor(c, sat, lit, isSurface = false, isContent = false)
            fun tuneSurf(c: Int): Int = tuneArgbColor(c, sat, lit, isSurface = true, isContent = false)
            fun tuneContent(c: Int): Int = tuneArgbColor(c, sat, lit, isSurface = false, isContent = true)

            val surfaceHigh = tuneSurf(attr(MaterialR.attr.colorSurfaceContainerHigh, 0xFF21262E.toInt(), 0xFFECEEF4.toInt()))
            val primary = tuneAccent(attr(androidx.appcompat.R.attr.colorPrimary, 0xFF8CD3FF.toInt(), 0xFF006494.toInt()))
            val activeGreen = if (isDark) 0xFF4ADE80.toInt() else 0xFF15803D.toInt()

            return OverlayThemeColors(
                themedContext = dynamic,
                isDark = isDark,
                surfaceContainer = ColorUtils.setAlphaComponent(
                    tuneSurf(attr(MaterialR.attr.colorSurfaceContainer, 0xFF1A1F26.toInt(), 0xFFF2F4FA.toInt())),
                    236
                ),
                surfaceContainerHigh = ColorUtils.setAlphaComponent(surfaceHigh, 238),
                surfaceContainerHighest = tuneSurf(attr(MaterialR.attr.colorSurfaceContainerHighest, 0xFF2C323B.toInt(), 0xFFE2E5EC.toInt())),
                onSurface = attr(MaterialR.attr.colorOnSurface, 0xFFE3E7EE.toInt(), 0xFF191C20.toInt()),
                onSurfaceVariant = attr(MaterialR.attr.colorOnSurfaceVariant, 0xFFC2C7D0.toInt(), 0xFF44474E.toInt()),
                outlineVariant = ColorUtils.setAlphaComponent(
                    tuneSurf(attr(MaterialR.attr.colorOutlineVariant, 0xFF434850.toInt(), 0xFFC4C6D0.toInt())),
                    160
                ),
                primary = primary,
                onPrimary = tuneContent(attr(MaterialR.attr.colorOnPrimary, 0xFF00344F.toInt(), Color.WHITE)),
                primaryContainer = tuneAccent(attr(MaterialR.attr.colorPrimaryContainer, 0xFF004B70.toInt(), 0xFFCBE6FF.toInt())),
                onPrimaryContainer = tuneContent(attr(MaterialR.attr.colorOnPrimaryContainer, 0xFFCBE6FF.toInt(), 0xFF001E30.toInt())),
                secondaryContainer = tuneAccent(attr(MaterialR.attr.colorSecondaryContainer, 0xFF364958.toInt(), 0xFFD6E4F3.toInt())),
                onSecondaryContainer = tuneContent(attr(MaterialR.attr.colorOnSecondaryContainer, 0xFFD6E4F3.toInt(), 0xFF0F1D2A.toInt())),
                tertiary = tuneAccent(attr(MaterialR.attr.colorTertiary, 0xFFFBC02D.toInt(), 0xFF8C5000.toInt())),
                tertiaryContainer = tuneAccent(attr(MaterialR.attr.colorTertiaryContainer, 0xFF6B3E00.toInt(), 0xFFFFDDB7.toInt())),
                onTertiaryContainer = tuneContent(attr(MaterialR.attr.colorOnTertiaryContainer, 0xFFFFDDB7.toInt(), 0xFF2C1600.toInt())),
                error = attr(androidx.appcompat.R.attr.colorError, 0xFFFFB4AB.toInt(), 0xFFBA1A1A.toInt()),
                errorContainer = attr(MaterialR.attr.colorErrorContainer, 0xFF93000A.toInt(), 0xFFFFDAD6.toInt()),
                onErrorContainer = attr(MaterialR.attr.colorOnErrorContainer, 0xFFFFDAD6.toInt(), 0xFF410002.toInt()),
                activeRunningGreen = activeGreen,
                activeRunningGreenContainer = ColorUtils.setAlphaComponent(activeGreen, if (isDark) 52 else 36),
                ripple = ColorUtils.setAlphaComponent(
                    attr(MaterialR.attr.colorOnSurface, 0xFFE3E7EE.toInt(), 0xFF191C20.toInt()),
                    if (isDark) 64 else 48
                )
            )
        }
    }
}
