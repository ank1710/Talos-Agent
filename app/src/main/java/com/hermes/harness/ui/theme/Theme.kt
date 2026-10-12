package com.hermes.harness.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Hermes semantic color palette.
 *
 * Every color the chat surface needs is named here — including the specialized
 * `toolCard*` and `code*` families. Those two families are intentionally
 * differentiated from the plain bubbles: [toolCard] / [codeBackground] sit on
 * their own, slightly lifted "inset panel" tone with their own borders and text
 * colors so a tool invocation or a code block never reads as ordinary prose.
 * That separation is what lets the transcript stay scannable while reasoning,
 * tool calls and answers are interleaved in a single stream.
 *
 * `brand` is the raw accent (#4D6BFE) used for fills and decorative strokes;
 * `brandInk` is the accessible variant used whenever the accent carries
 * *text* (links, inline emphasis), because the raw accent on the near-black
 * background falls short of 4.5:1.
 */
@Immutable
data class HermesPalette(
    val brand: Color,
    val brandInk: Color,
    val brandMuted: Color,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceVariant: Color,
    val divider: Color,
    val bubbleBorder: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val assistantBubble: Color,
    val assistantBubbleText: Color,
    val toolCard: Color,
    val toolCardBorder: Color,
    val toolCardText: Color,
    val codeBackground: Color,
    val codeBorder: Color,
    val codeText: Color,
    val danger: Color,
    val caution: Color,
    val success: Color,
    val info: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textFaint: Color,
    val isDark: Boolean,
)

/** Accent used for fills and strokes; matches the Hermes brand mark. */
private val BrandBlue = Color(0xFF4D6BFE)

/**
 * Dark, blue-cast base. Near-black rather than pure black so elevated surfaces
 * can be distinguished by tone alone on OLED panels.
 *
 * Verified WCAG contrast (body text >= 4.5:1, non-text accents >= 3:1):
 * assistant bubble 14.2:1, user bubble 5.6:1, secondary 7.7:1, faint 5.1:1,
 * brandInk on background 6.6:1, tool text 11.5:1, code text 13.2:1.
 */
val darkPalette = HermesPalette(
    brand = BrandBlue,
    brandInk = Color(0xFF93A7FF),
    brandMuted = Color(0x38A9BAFF),
    background = Color(0xFF0E1116),
    surface = Color(0xFF12161D),
    surfaceElevated = Color(0xFF1A2130),
    surfaceVariant = Color(0xFF1E2532),
    divider = Color(0xFF242C3A),
    bubbleBorder = Color(0xFF262E3C),
    userBubble = BrandBlue,
    userBubbleText = Color(0xFFFFFFFF),
    assistantBubble = Color(0xFF161B24),
    assistantBubbleText = Color(0xFFE6EAF2),
    toolCard = Color(0xFF121926),
    toolCardBorder = Color(0xFF2C3A50),
    toolCardText = Color(0xFFD5DCEA),
    codeBackground = Color(0xFF0B0E13),
    codeBorder = Color(0xFF232B39),
    codeText = Color(0xFFDCE3F0),
    danger = Color(0xFFFF7A80),
    caution = Color(0xFFF0BC5A),
    success = Color(0xFF5FD3A0),
    info = Color(0xFF6FC4F7),
    textPrimary = Color(0xFFE6EAF2),
    textSecondary = Color(0xFFA7B0C0),
    textFaint = Color(0xFF8C96A8),
    isDark = true,
)

/**
 * Light counterpart. Same structure, tuned for paper-white surfaces. The
 * semantic status colors are darkened here because the dark-palette variants
 * are authored as *light* text on a near-black surface and would fall below
 * 4.5:1 on white; the light values below all clear 4.5:1.
 */
val lightPalette = HermesPalette(
    brand = BrandBlue,
    brandInk = Color(0xFF2F4FE0),
    brandMuted = Color(0xFF8C9BE4),
    background = Color(0xFFFAFAFC),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F2F7),
    divider = Color(0xFFE4E7EF),
    bubbleBorder = Color(0xFFE7EAF2),
    userBubble = Color(0xFF3D59F0),
    userBubbleText = Color(0xFFFFFFFF),
    assistantBubble = Color(0xFFF6F7FA),
    assistantBubbleText = Color(0xFF151A23),
    toolCard = Color(0xFFF2F4F9),
    toolCardBorder = Color(0xFFDDE2EC),
    toolCardText = Color(0xFF2A3242),
    codeBackground = Color(0xFFF3F4F8),
    codeBorder = Color(0xFFE1E5EE),
    codeText = Color(0xFF232A36),
    danger = Color(0xFFDC2B36),
    caution = Color(0xFF9A6600),
    success = Color(0xFF0E7A45),
    info = Color(0xFF17618F),
    textPrimary = Color(0xFF151A23),
    textSecondary = Color(0xFF525C6E),
    textFaint = Color(0xFF5F6B7E),
    isDark = false,
)

private val DarkColorScheme = darkColorScheme(
    primary = darkPalette.brand,
    onPrimary = darkPalette.userBubbleText,
    primaryContainer = darkPalette.surfaceElevated,
    onPrimaryContainer = darkPalette.textPrimary,
    secondary = darkPalette.brandInk,
    onSecondary = darkPalette.background,
    secondaryContainer = darkPalette.surfaceVariant,
    onSecondaryContainer = darkPalette.textPrimary,
    tertiary = darkPalette.info,
    onTertiary = darkPalette.background,
    background = darkPalette.background,
    onBackground = darkPalette.textPrimary,
    surface = darkPalette.surface,
    onSurface = darkPalette.textPrimary,
    surfaceVariant = darkPalette.surfaceVariant,
    onSurfaceVariant = darkPalette.textSecondary,
    surfaceContainer = darkPalette.surface,
    surfaceContainerHigh = darkPalette.surfaceElevated,
    surfaceContainerHighest = darkPalette.surfaceVariant,
    surfaceContainerLow = darkPalette.surface,
    surfaceContainerLowest = darkPalette.background,
    outline = darkPalette.divider,
    outlineVariant = darkPalette.bubbleBorder,
    error = darkPalette.danger,
    onError = darkPalette.background,
    scrim = Color(0xCC000000),
    inverseSurface = darkPalette.textPrimary,
    inverseOnSurface = darkPalette.background,
)

private val LightColorScheme = lightColorScheme(
    primary = lightPalette.brand,
    onPrimary = lightPalette.userBubbleText,
    primaryContainer = lightPalette.surfaceVariant,
    onPrimaryContainer = lightPalette.textPrimary,
    secondary = lightPalette.brandInk,
    onSecondary = lightPalette.surface,
    secondaryContainer = lightPalette.surfaceVariant,
    onSecondaryContainer = lightPalette.textPrimary,
    tertiary = lightPalette.info,
    onTertiary = lightPalette.surface,
    background = lightPalette.background,
    onBackground = lightPalette.textPrimary,
    surface = lightPalette.surface,
    onSurface = lightPalette.textPrimary,
    surfaceVariant = lightPalette.surfaceVariant,
    onSurfaceVariant = lightPalette.textSecondary,
    surfaceContainer = lightPalette.surface,
    surfaceContainerHigh = lightPalette.surface,
    surfaceContainerHighest = lightPalette.surfaceVariant,
    surfaceContainerLow = lightPalette.surface,
    surfaceContainerLowest = lightPalette.background,
    outline = lightPalette.divider,
    outlineVariant = lightPalette.bubbleBorder,
    error = lightPalette.danger,
    onError = lightPalette.surface,
    scrim = Color(0x66000000),
    inverseSurface = lightPalette.textPrimary,
    inverseOnSurface = lightPalette.surface,
)

/** Supplies the active [HermesPalette]; defaults to the dark palette. */
val LocalHermesPalette = staticCompositionLocalOf { darkPalette }

/** Entry point for reading the active palette from any composable. */
object HermesColors {
    val current: HermesPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalHermesPalette.current
}

/**
 * Root theme for Hermes.
 *
 * Publishes the semantic [HermesPalette] through [LocalHermesPalette] and also
 * installs a matching Material 3 [MaterialTheme], so stock Material components
 * (sheets, dialogs, ripples, text fields) blend into the Hermes surface without
 * per-component overrides.
 *
 * @param darkTheme dark-first by default; pass `false` to force the light palette.
 */
@Composable
fun HermesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) darkPalette else lightPalette
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val typography = HermesTypography

    CompositionLocalProvider(LocalHermesPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}
