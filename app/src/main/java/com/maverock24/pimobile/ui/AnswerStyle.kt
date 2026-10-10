package com.maverock24.pimobile.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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
 * The dark side no longer follows that accent: it now comes in four named
 * themes, Midnight first among them, each a complete palette with its own night
 * sky. Light mode is not one of them: it always uses the lab's indigo and its
 * own flat background.
 *
 * Change a value here and the whole answer view follows.
 */

/**
 * One named look for the dark side of the app: the colour scheme it paints
 * with, the night sky drawn behind it, and the name the settings picker shows.
 * Light mode ignores every field of it.
 */
data class AnswerTheme(
    val label: String,
    val scheme: ColorScheme,
    val skyTop: Color,
    val skyMid: Color,
    val skyBottom: Color,
    val skyHorizonGlow: Color,
    val skyMiddleGlow: Color,
)

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
     * The two accents that are not a theme's own: Midnight's cyan-teal, which
     * matches the media app's midnight sky, and the light scheme's indigo,
     * which is the design lab's. The other themes carry their accent in their
     * scheme below.
     */
    private val accentDark = Color(0xFF0AD6FF)
    private val accentLight = Color(0xFF3562D6)

    /**
     * Fills every colour role Material3 can fall back on from the handful a theme
     * states, so nothing on screen paints with Material's purple baseline. The
     * baseline is what a plain notice bar, a tonal button and a text field would
     * otherwise use, because a scheme that sets only its core slots leaves every
     * other role at the default.
     *
     * Every derived value comes from the theme's own colours so the result still
     * looks like that theme: the containers are its surface lifted toward its
     * accent or its error colour, their "on" colours are its own foreground,
     * secondary is its variant surface, tertiary is its accent, and the surface
     * container steps sit between its background and its surface. Nothing is
     * copied from the baseline.
     */
    private fun deriveScheme(base: ColorScheme): ColorScheme {
        val accent = base.primary
        val surface = base.surface
        val background = base.background
        val foreground = base.onBackground
        val error = base.error
        // A dark fill gets light text and a light fill dark text, so the error
        // colour reads under the dark palettes and under the light scheme alike.
        val onFill = if (error.luminance() > 0.5f) Color(0xFF14181F) else Color(0xFFF6F8FB)
        return base.copy(
            primaryContainer = lerp(surface, accent, 0.22f),
            onPrimaryContainer = foreground,
            inversePrimary = accent,
            surfaceTint = accent,
            secondary = base.surfaceVariant,
            onSecondary = base.onSurfaceVariant,
            secondaryContainer = lerp(surface, accent, 0.14f),
            onSecondaryContainer = foreground,
            tertiary = accent,
            onTertiary = base.onPrimary,
            tertiaryContainer = lerp(surface, accent, 0.30f),
            onTertiaryContainer = foreground,
            errorContainer = lerp(surface, error, 0.20f),
            onErrorContainer = foreground,
            onError = onFill,
            inverseSurface = surface,
            inverseOnSurface = background,
            scrim = Color(0xFF000000),
            surfaceDim = background,
            surfaceBright = lerp(surface, foreground, 0.05f),
            surfaceContainerLowest = background,
            surfaceContainerLow = lerp(background, surface, 0.25f),
            surfaceContainer = lerp(background, surface, 0.5f),
            surfaceContainerHigh = lerp(background, surface, 0.75f),
            surfaceContainerHighest = surface,
        )
    }

    /**
     * Midnight, the original dark look: taken from mobile-media-app's
     * `src/app.css` @theme block with the HSL converted rather than rounded by
     * eye: background hsl(218 55% 6%), card hsl(218 50% 9%), muted hsl(218 35%
     * 11%), border hsl(218 40% 15%), foreground hsl(210 30% 94%), muted
     * foreground hsl(210 20% 48%), accent hsl(190 100% 52%), destructive hsl(0
     * 70% 55%).
     *
     * Surface is no longer the same colour as the background in this scheme, so
     * anything drawn as a surface now reads as a panel instead of vanishing into
     * the page.
     *
     * Its sky is the media app's layered background: a navy gradient down the
     * page with a cyan glow at the horizon and a weaker one below the middle,
     * converted from its HSL the same way, alphas included.
     */
    val midnight = AnswerTheme(
        label = "Midnight",
        scheme = deriveScheme(
            darkColorScheme(
                primary = accentDark,
                onPrimary = Color(0xFF060D18),
                background = Color(0xFF070D18),
                onBackground = Color(0xFFEBF0F4),
                surface = Color(0xFF0B1422),
                onSurface = Color(0xFFE6EBF0),
                surfaceVariant = Color(0xFF121926),
                onSurfaceVariant = Color(0xFF7E94A9),
                outline = Color(0xFF172236),
                outlineVariant = Color(0xFF141E2E),
                error = Color(0xFFE35F5F),
            ),
        ),
        skyTop = Color(0xFF091120),
        skyMid = Color(0xFF050B14),
        skyBottom = Color(0xFF040911),
        skyHorizonGlow = Color(0x99093453),
        skyMiddleGlow = Color(0x5906516B),
    )

    /** Indigo: a brighter blue accent over colder, bluer surfaces. */
    val indigo = AnswerTheme(
        label = "Indigo",
        scheme = deriveScheme(
            darkColorScheme(
                primary = Color(0xFF7AA2F7),
                onPrimary = Color(0xFF060C18),
                background = Color(0xFF080D16),
                onBackground = Color(0xFFEBEEF4),
                surface = Color(0xFF0E1420),
                onSurface = Color(0xFFE6E9F0),
                surfaceVariant = Color(0xFF151A23),
                onSurfaceVariant = Color(0xFF7E8CA9),
                outline = Color(0xFF1B2232),
                outlineVariant = Color(0xFF191E29),
                error = Color(0xFFE35F5F),
            ),
        ),
        skyTop = Color(0xFF0B111E),
        skyMid = Color(0xFF060B13),
        skyBottom = Color(0xFF050910),
        skyHorizonGlow = Color(0x99090F53),
        skyMiddleGlow = Color(0x59061F6B),
    )

    /** Amber: a warm accent over near-black browns. */
    val amber = AnswerTheme(
        label = "Amber",
        scheme = deriveScheme(
            darkColorScheme(
                primary = Color(0xFFF9A91F),
                onPrimary = Color(0xFF180F06),
                background = Color(0xFF150F0A),
                onBackground = Color(0xFFF4EFEB),
                surface = Color(0xFF1E1610),
                onSurface = Color(0xFFF0EAE6),
                surfaceVariant = Color(0xFF201C18),
                onSurfaceVariant = Color(0xFF937962),
                outline = Color(0xFF2E261F),
                outlineVariant = Color(0xFF26211C),
                error = Color(0xFFDD3C3C),
            ),
        ),
        skyTop = Color(0xFF1C140D),
        skyMid = Color(0xFF120C08),
        skyBottom = Color(0xFF0F0906),
        skyHorizonGlow = Color(0x99534A09),
        skyMiddleGlow = Color(0x596B4E06),
    )

    /** Forest: a green accent over near-black greens. */
    val forest = AnswerTheme(
        label = "Forest",
        scheme = deriveScheme(
            darkColorScheme(
                primary = Color(0xFF2EB877),
                onPrimary = Color(0xFF061811),
                background = Color(0xFF0A1510),
                onBackground = Color(0xFFEBF4F0),
                surface = Color(0xFF101E18),
                onSurface = Color(0xFFE6F0EB),
                surfaceVariant = Color(0xFF18201D),
                onSurfaceVariant = Color(0xFF62937E),
                outline = Color(0xFF1F2E28),
                outlineVariant = Color(0xFF1C2622),
                error = Color(0xFFDD3C3C),
            ),
        ),
        skyTop = Color(0xFF0D1C16),
        skyMid = Color(0xFF08120E),
        skyBottom = Color(0xFF060F0B),
        skyHorizonGlow = Color(0x99095343),
        skyMiddleGlow = Color(0x59066B44),
    )

    /**
     * Every dark palette by the name the settings store keeps. It is a linked
     * map so the picker keeps this order rather than the hash order.
     */
    val themes: Map<String, AnswerTheme> = linkedMapOf(
        "midnight" to midnight,
        "indigo" to indigo,
        "amber" to amber,
        "forest" to forest,
    )

    /** The theme a fresh install starts on. */
    const val defaultTheme = "midnight"

    /** The palette for a stored name; anything unknown falls back to Midnight. */
    fun theme(name: String): AnswerTheme = themes[name] ?: midnight

    val lightScheme = deriveScheme(
        lightColorScheme(
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
            error = Color(0xFFDD3C3C),
        ),
    )

    /** Inline code chip background: accent at low alpha. */
    fun chipBackground(isDark: Boolean): Color =
        if (isDark) Color(0x240AD6FF) else Color(0x1A3562D6)

    fun diffAdd(isDark: Boolean): Color = if (isDark) Color(0x2886D99A) else Color(0x1F2F9E5B)

    fun diffDel(isDark: Boolean): Color = if (isDark) Color(0x28F08A8A) else Color(0x1FB4453F)
}
