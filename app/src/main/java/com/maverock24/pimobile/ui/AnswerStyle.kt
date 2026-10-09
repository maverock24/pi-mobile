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
 * The dark scheme no longer follows that accent: it now matches the media app's
 * midnight sky instead, which is a different palette in the same spirit. The
 * light scheme still uses the lab's indigo.
 *
 * Change a value here and the whole answer view follows.
 */
object AnswerStyle {

    // Raised on 2026-10-04: 14sp read as small on a phone held at arm's length.
    val bodySize = 16.sp

    /**
     * 1.5 lines. The lab choice was 1.42 (tight), which reads as cramped once a
     * real answer has several paragraphs; 1.5 is the smallest value that still
     * keeps prose comfortable at 14sp.
     */
    val bodyLineHeight = 24.sp

    /** 0.57em at 14sp, raised from the lab's 0.35em for the same reason. */
    val paragraphGap = 10.dp

    /** Extra room above structural blocks so they do not run into prose. */
    val blockGap = 12.dp

    /** Space above a heading, on top of the paragraph gap. */
    val headingGap = 14.dp

    /** Space between list items. */
    val listItemGap = 8.dp

    /** Minimum height for anything meant to be tapped. */
    val buttonHeight = 56.dp

    /**
     * How far the face of a solid control sits above its base. The base is drawn
     * inside the control's own bounds, so the depth comes out of the face rather
     * than out of the row: a control asked for [buttonHeight] keeps that height.
     * One left to size itself has nothing to give up and grows by this much, so
     * every control that draws a key asks for [buttonHeight].
     */
    val keyDepth = 5.dp

    /**
     * Leading accent bar. It marks a quoted passage inside an answer and the
     * prompt of a turn in the transcript, so the two read the same way.
     */
    val accentBar = 3.dp

    /** Space between that bar and the text it marks. */
    val accentBarGap = 10.dp

    /**
     * The prompt that opened a turn. It is a step larger and heavier than the
     * answer body and sits on its own surface with a border, so a prompt reads as
     * the heading of the answer under it and a column of prompts is separable at a
     * glance instead of blending into the prose.
     */
    val promptSize = 17.sp
    val promptLineHeight = 26.sp
    val promptRadius = 10.dp

    /** Room inside that surface. */
    val promptPadding = 12.dp

    /** Space above it, on top of the list's own gap, so a prompt starts a block. */
    val promptGap = 8.dp

    /** Indent step for one bullet level. */
    val bulletIndent = 20.dp

    /** 1.18em at 14sp. */
    val headingSize = 19.sp

    val codeSize = 14.sp
    val codeRadius = 12.dp
    val codePadding = 10.dp

    /** 1.18em of the body size, used for inline code. */
    val inlineCodeScale = 0.92f

    /** Space between two answers in the stream. */
    val answerGap = 26.dp

    /**
     * 54 characters at 14sp. Average glyph advance for UI sans is about 0.53em,
     * so 54 * 14 * 0.53 ≈ 400dp. Wider screens (landscape, tablets) get a
     * readable column instead of 120-character lines.
     */
    val measure = 460.dp

    /**
     * The dark accent is a cyan-teal rather than the lab's indigo, because the
     * dark scheme below follows the media app's midnight sky: navy surfaces,
     * hairline navy borders, and one saturated colour used sparingly. The light
     * scheme keeps the indigo.
     */
    private val accentDark = Color(0xFF0AD6FF)
    private val accentLight = Color(0xFF3562D6)

    /**
     * Midnight sky, taken from mobile-media-app's `src/app.css` @theme block with
     * the HSL converted rather than rounded by eye: background hsl(218 55% 6%),
     * card hsl(218 50% 9%), muted hsl(218 35% 11%), border hsl(218 40% 15%),
     * foreground hsl(210 30% 94%), muted foreground hsl(210 20% 48%), accent
     * hsl(190 100% 52%), destructive hsl(0 70% 55%).
     *
     * Surface is no longer the same colour as the background in this scheme, so
     * anything drawn as a surface now reads as a panel instead of vanishing into
     * the page.
     */
    val darkScheme = darkColorScheme(
        primary = accentDark,
        onPrimary = Color(0xFF060D18),
        background = Color(0xFF070D18),
        onBackground = Color(0xFFEBF0F4),
        surface = Color(0xFF0B1422),
        onSurface = Color(0xFFE6EBF0),
        surfaceVariant = Color(0xFF121926),
        onSurfaceVariant = Color(0xFF627A93),
        outline = Color(0xFF172236),
        outlineVariant = Color(0xFF141E2E),
        error = Color(0xFFDD3C3C),
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

    /**
     * The night sky the dark scheme sits on, from the media app's layered
     * background: a navy gradient down the page with a cyan glow at the horizon
     * and a weaker one below the middle. Converted from its HSL the same way as
     * the scheme above, alphas included, so the two apps are the same sky rather
     * than two similar ones.
     */
    val skyTop = Color(0xFF091120)
    val skyMid = Color(0xFF050B14)
    val skyBottom = Color(0xFF040911)
    val skyHorizonGlow = Color(0x99093453)
    val skyMiddleGlow = Color(0x5906516B)

    /** Inline code chip background: accent at low alpha. */
    fun chipBackground(isDark: Boolean): Color =
        if (isDark) Color(0x240AD6FF) else Color(0x1A3562D6)

    fun diffAdd(isDark: Boolean): Color = if (isDark) Color(0x2886D99A) else Color(0x1F2F9E5B)

    fun diffDel(isDark: Boolean): Color = if (isDark) Color(0x28F08A8A) else Color(0x1FB4453F)
}
