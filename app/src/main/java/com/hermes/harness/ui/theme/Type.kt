package com.hermes.harness.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Hermes type scale.
 *
 * Built from the stock Material 3 [Typography] with a small number of overrides
 * so the chat transcript reads as prose while chrome (labels, metadata) stays
 * quiet. Prose uses [FontFamily.Default] — the platform UI font — because the
 * app ships no font resources; the monospace family below is only ever used for
 * code and inline code spans.
 *
 * Overridden roles:
 *  - [Typography.bodyLarge]   message body text
 *  - [Typography.bodyMedium]  secondary copy and dense list rows
 *  - [Typography.titleMedium] section headers
 *  - [Typography.titleLarge]  screen titles
 *  - [Typography.labelSmall]  metadata chips: tool names, token counts, timestamps
 */
private val DefaultTypography = Typography()

/** Small-caps-style metadata label, reused by tool cards and code fences. */
private val HermesLabelSmall = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.6.sp,
)

/**
 * Monospace style for fenced code blocks. Not part of [HermesTypography]: code
 * is addressed directly through [HermesCodeTextStyle] (or the `codeStyle`
 * parameter of `MarkdownText`) because it must stay fixed regardless of the
 * prose role it is embedded in.
 */
val HermesCodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.sp,
)

/** App-wide type scale consumed by `HermesTheme`. */
val HermesTypography: Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = HermesLabelSmall,
)
