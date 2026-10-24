package com.djimatchlogger.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Modern Aero palettes - Sexy, vibrant dashboard with gradients.
 */
object MatchPalette {

    // --- Midnight Aero (Dark) ---
    private object Dark {
        val Page = Color(0xFF020617)
        val PageTop = Color(0xFF0B1224)
        val Panel = Color(0xFF0F172A)
        val PanelBorder = Color(0xFF1E293B)
        val Text = Color(0xFFFFFFFF)
        val Muted = Color(0xFF94A3B8)
        val PanelTitle = Color(0xFF64748B)
        val HomeEnd = Color(0xFF1D4ED8)
        val AwayEnd = Color(0xFFB91C1C)
        val NeutralEnd = Color(0xFF1E293B)
        val Disabled = Color(0xFF1E293B)
        val OnDisabled = Color(0xFF475569)
    }

    // --- Daylight Aero (Light) - High visibility for outdoors ---
    private object Light {
        val Page = Color(0xFFF8FAFC)
        val PageTop = Color(0xFFE2E8F0)
        val Panel = Color(0xFFFFFFFF)
        val PanelBorder = Color(0xFFCBD5E1)
        val Text = Color(0xFF0F172A)
        val Muted = Color(0xFF64748B)
        val PanelTitle = Color(0xFF94A3B8)
        val HomeEnd = Color(0xFF2563EB)
        val AwayEnd = Color(0xFFDC2626)
        val NeutralEnd = Color(0xFF94A3B8)
        val Disabled = Color(0xFFE2E8F0)
        val OnDisabled = Color(0xFF94A3B8)
    }

    var isDark = true

    /** Deep Premium background. */
    val Page get() = if (isDark) Dark.Page else Light.Page
    val PageTop get() = if (isDark) Dark.PageTop else Light.PageTop
    
    /** High-end panels. */
    val Panel get() = if (isDark) Dark.Panel else Light.Panel
    val PanelBorder get() = if (isDark) Dark.PanelBorder else Light.PanelBorder

    /** Contrast type. */
    val Text get() = if (isDark) Dark.Text else Light.Text
    val Muted get() = if (isDark) Dark.Muted else Light.Muted
    val PanelTitle get() = if (isDark) Dark.PanelTitle else Light.PanelTitle

    /** Home: Deep Blue Gradient. */
    val Home = Color(0xFF3B82F6)
    val HomeEnd get() = if (isDark) Dark.HomeEnd else Light.HomeEnd
    val HomeText get() = if (isDark) Color(0xFFDBEAFE) else Color(0xFF1E3A8A)
    val HomeBox get() = if (isDark) Color(0xFF020617) else Color(0xFFEFF6FF)
    
    /** Away: Deep Red Gradient. */
    val Away = Color(0xFFEF4444)
    val AwayEnd get() = if (isDark) Dark.AwayEnd else Light.AwayEnd
    val AwayText get() = if (isDark) Color(0xFFFEE2E2) else Color(0xFF7F1D1D)
    val AwayBox get() = if (isDark) Color(0xFF020617) else Color(0xFFFEF2F2)

    /** Control gradients (Top to Bottom). */
    val NextPeriod = Color(0xFF313690)
    val NextPeriodEnd = Color(0xFF1E2150)
    val Pause = Color(0xFFFF6336)
    val PauseEnd = Color(0xFFD43D11)
    val EndMatch = Color(0xFFA90214)
    val EndMatchEnd = Color(0xFF6B010D)
    val Go = Color(0xFF22C55E)
    val GoEnd = Color(0xFF15803D)

    /** Functional. */
    val Neutral = Color(0xFF475569)
    val NeutralEnd get() = if (isDark) Dark.NeutralEnd else Light.NeutralEnd
    val Undo get() = if (isDark) Color(0xFF334155) else Color(0xFF94A3B8)
    val Danger = Color(0xFFEF4444)
    val Disabled get() = if (isDark) Dark.Disabled else Light.Disabled
    val OnDisabled get() = if (isDark) Dark.OnDisabled else Light.OnDisabled

    /** Cards. */
    val YellowCard = Color(0xFFFACC15)
    val OnYellowCard = Color(0xFF000000)
    val RedCard = Color(0xFFE11D48)
}

fun matchColors(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = MatchPalette.Home,
        onPrimary = Color.White,
        secondary = MatchPalette.NextPeriod,
        onSecondary = Color.White,
        tertiary = MatchPalette.Away,
        onTertiary = Color.White,
        background = Color(0xFF020617),
        onBackground = Color.White,
        surface = Color(0xFF0F172A),
        onSurface = Color.White,
        error = Color(0xFFEF4444),
    )
} else {
    lightColorScheme(
        primary = MatchPalette.Home,
        onPrimary = Color.White,
        secondary = MatchPalette.NextPeriod,
        onSecondary = Color.White,
        tertiary = MatchPalette.Away,
        onTertiary = Color.White,
        background = Color(0xFFF8FAFC),
        onBackground = Color(0xFF0F172A),
        surface = Color.White,
        onSurface = Color(0xFF0F172A),
        error = Color(0xFFEF4444),
    )
}
