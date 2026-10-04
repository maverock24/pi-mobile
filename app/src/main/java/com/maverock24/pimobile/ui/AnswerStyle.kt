package com.maverock24.pimobile.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens for rendering answers, taken from the design lab choices saved
 * on 2026-10-04 (pi-remote-design-lab/out/choices-latest.json):
 *
 *   layout document · font sans · scale compact (14 / 1.42) · rhythm tight
 *   headings hierarchy · inline chip · code bordered · lists bullets
 *   links underline · citations inline · diff inline · tools hidden
 *   think hidden · stream pulse · status shimmer · accent indigo · measure 54ch
 *
 * Change a value here and the whole answer view follows.
 */
object AnswerStyle {

    val bodySize = 14.sp
    val bodyLineHeight = 20.sp

    /** 0.35em at 14sp. */
    val paragraphGap = 6.dp

    /** 1.18em at 14sp. */
    val headingSize = 16.5.sp

    val codeSize = 12.5.sp
    val codeRadius = 10.dp
    val codePadding = 10.dp

    /** 1.18em of the body size, used for inline code. */
    val inlineCodeScale = 0.92f

    /** Space between two answers in the stream. */
    val answerGap = 22.dp

    /**
     * 54 characters at 14sp. Average glyph advance for UI sans is about 0.53em,
     * so 54 * 14 * 0.53 ≈ 400dp. Wider screens (landscape, tablets) get a
     * readable column instead of 120-character lines.
     */
    val measure = 400.dp

    val indent = 22.dp

    // Indigo accent, matching the lab's default.
    private val accentDark = Color(0xFF7AA2F7)
    private val accentLight = Color(0xFF3562D6)

    val darkScheme = darkColorScheme(
        primary = accentDark,
        onPrimary = Color(0xFF08111F),
        background = Color(0xFF0E1014),
        onBackground = Color(0xFFE7E9EE),
        surface = Color(0xFF0E1014),
        onSurface = Color(0xFFE7E9EE),
        surfaceVariant = Color(0xFF1D222B),
        onSurfaceVariant = Color(0xFF939BAB),
        outline = Color(0xFF262D38),
        outlineVariant = Color(0xFF1D222B),
    )

    val lightScheme = lightColorScheme(
        primary = accentLight,
        onPrimary = Color.White,
        background = Color(0xFFF6F7FA),
        onBackground = Color(0xFF14181F),
        surface = Color(0xFFF6F7FA),
        onSurface = Color(0xFF14181F),
        surfaceVariant = Color(0xFFF1F3F7),
        onSurfaceVariant = Color(0xFF5C6572),
        outline = Color(0xFFDFE3EA),
        outlineVariant = Color(0xFFE7EAF0),
    )

    /** Inline code chip background: accent at low alpha. */
    fun chipBackground(isDark: Boolean): Color =
        if (isDark) Color(0x247AA2F7) else Color(0x1A3562D6)

    fun diffAdd(isDark: Boolean): Color = if (isDark) Color(0x2886D99A) else Color(0x1F2F9E5B)

    fun diffDel(isDark: Boolean): Color = if (isDark) Color(0x28F08A8A) else Color(0x1FB4453F)
}
