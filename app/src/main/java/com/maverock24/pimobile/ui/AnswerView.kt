@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.maverock24.pimobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.Selection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Renders one answer: markdown blocks plus inline styling, with the tokens from
 * [AnswerStyle]. Tools and thinking are intentionally never rendered.
 */
/** A link found in an answer, ready to be rendered as a button. */
private data class AnswerLink(val url: String, val label: String?)

private val linkRegex = Regex(
    """\[([^\]\n]+)\]\((https?://[^)\s]+)\)|(https?://[^\s<>()\[\]{}"']+)""",
)

/** Every http(s) link in the answer, deduplicated, with any markdown label kept. */
private fun extractLinks(text: String): List<AnswerLink> {
    val found = LinkedHashMap<String, AnswerLink>()
    for (match in linkRegex.findAll(text)) {
        val raw = match.groupValues[2].ifBlank { match.groupValues[3] }
        val url = raw.trimEnd('.', ',', ';', ')', ':')
        if (!url.startsWith("http")) continue
        val label = match.groupValues[1].ifBlank { null }
        if (!found.containsKey(url)) found[url] = AnswerLink(url, label)
    }
    return found.values.toList()
}

private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')

/** Display form: no scheme, truncated in the middle so the end stays readable. */
private fun trimUrl(url: String): String {
    val withoutScheme = url.removePrefix("https://").removePrefix("http://")
    return if (withoutScheme.length <= 64) withoutScheme
    else withoutScheme.take(40) + "…" + withoutScheme.takeLast(18)
}

@Composable
fun AnswerView(
    text: String,
    modifier: Modifier = Modifier,
    onSelectionChange: ((String?) -> Unit)? = null,
) {
    val blocks = remember(text) { parseAnswerBlocks(text) }
    val links = remember(text) { extractLinks(text) }
    val scheme = MaterialTheme.colorScheme
    val linkColor = scheme.primary
    val chipColor = AnswerStyle.chipBackground(scheme.background.luminance() < 0.5f)

    // Compose reports a selection's offsets within the run it belongs to, not
    // across the answer, so an excerpt is taken from the run that owns it. The
    // owner is remembered so a run clearing its own selection cannot wipe an
    // excerpt that a newer selection in another run has already replaced.
    var excerptOwner by remember { mutableStateOf<Any?>(null) }
    var excerpt by remember { mutableStateOf<String?>(null) }
    val reportExcerpt: (Any, String?) -> Unit = { owner, picked ->
        if (!picked.isNullOrBlank()) {
            excerptOwner = owner
            excerpt = picked
        } else if (excerptOwner === owner) {
            excerptOwner = null
            excerpt = null
        }
        onSelectionChange?.invoke(excerpt)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AnswerStyle.paragraphGap),
    ) {
        blocks.forEach { block ->
            when (block) {
                is AnswerBlock.Heading -> {
                    val rendered = inlineText(block.text, linkColor, chipColor)
                    SelectableRun(rendered.text, reportExcerpt) {
                        Text(
                            text = rendered,
                            fontWeight = FontWeight.Bold,
                            fontSize = AnswerStyle.headingSize,
                            lineHeight = AnswerStyle.headingSize * 1.3,
                            color = scheme.onBackground,
                            modifier = Modifier.padding(top = AnswerStyle.headingGap),
                        )
                    }
                }

                is AnswerBlock.Paragraph -> {
                    val rendered = inlineText(block.text, linkColor, chipColor)
                    SelectableRun(rendered.text, reportExcerpt) {
                        Text(
                            text = rendered,
                            fontSize = AnswerStyle.bodySize,
                            lineHeight = AnswerStyle.bodyLineHeight,
                            color = scheme.onBackground,
                        )
                    }
                }

                is AnswerBlock.Quote -> {
                    val rendered = inlineText(block.text, linkColor, chipColor)
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp, bottom = 2.dp, end = AnswerStyle.accentBarGap)
                                .width(AnswerStyle.accentBar)
                                .heightIn(min = 18.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(scheme.primary),
                        )
                        SelectableRun(rendered.text, reportExcerpt) {
                            Text(
                                text = rendered,
                                fontSize = AnswerStyle.bodySize,
                                lineHeight = AnswerStyle.bodyLineHeight,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }

                is AnswerBlock.Bullets -> Column(
                    verticalArrangement = Arrangement.spacedBy(AnswerStyle.listItemGap),
                ) {
                    var counter = 0
                    block.items.forEach { item ->
                        if (!item.marker) counter++
                        val rendered = inlineText(item.text, linkColor, chipColor)
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.padding(start = AnswerStyle.bulletIndent * item.level),
                        ) {
                            Text(
                                text = if (block.ordered && !item.marker) "$counter." else "•",
                                color = if (block.ordered && !item.marker) linkColor else scheme.onSurfaceVariant,
                                fontSize = AnswerStyle.bodySize,
                                lineHeight = AnswerStyle.bodyLineHeight,
                                modifier = Modifier.width(AnswerStyle.bulletIndent),
                            )
                            SelectableRun(rendered.text, reportExcerpt) {
                                Text(
                                    text = rendered,
                                    fontSize = AnswerStyle.bodySize,
                                    lineHeight = AnswerStyle.bodyLineHeight,
                                    color = scheme.onBackground,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                        }
                    }
                }

                is AnswerBlock.Code -> Surface(
                    modifier = Modifier.padding(top = AnswerStyle.blockGap),
                    shape = RoundedCornerShape(AnswerStyle.codeRadius),
                    color = scheme.surfaceVariant,
                    border = BorderStroke(1.dp, scheme.outline),
                ) {
                    SelectableRun(block.code, reportExcerpt) {
                        Text(
                            text = block.code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = AnswerStyle.codeSize,
                            lineHeight = AnswerStyle.codeSize * 1.55,
                            color = scheme.onBackground,
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(AnswerStyle.codePadding),
                        )
                    }
                }

                is AnswerBlock.Diff -> Box(modifier = Modifier.padding(top = AnswerStyle.blockGap)) {
                    DiffBlock(block, reportExcerpt)
                }

                is AnswerBlock.Table -> Box(modifier = Modifier.padding(top = AnswerStyle.blockGap)) {
                    TableBlock(block, reportExcerpt)
                }

                AnswerBlock.Rule -> Box(
                    modifier = Modifier
                        .padding(vertical = AnswerStyle.blockGap)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(scheme.outline),
                )
            }
        }

        // Links get their own buttons: tapping a URL in running text on a
        // phone while walking is exactly the case that fails.
        if (links.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(top = AnswerStyle.blockGap),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (links.size == 1) "Link" else "Links",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
                links.forEach { link -> LinkButton(link) }
            }
        }
    }
}

/**
 * One run of selectable text. Compose reports a selection's offsets within the
 * run it belongs to, so each run keeps its own container and knows the exact
 * characters it draws. Taking the excerpt from that string is what stops the
 * offsets of the rendered text being read as offsets of the answer's markdown,
 * which no longer has the same characters once the styling is stripped.
 */
@Composable
private fun SelectableRun(
    renderedText: String,
    onExcerpt: (Any, String?) -> Unit,
    content: @Composable () -> Unit,
) {
    val token = remember { Any() }
    var selection by remember { mutableStateOf<Selection?>(null) }
    // The overload that hands the app the selection is internal in this Compose
    // version, so this file suppresses the visibility check. The app is pinned to
    // one Compose BOM, and no custom selection UI is built.
    SelectionContainer(
        Modifier,
        selection,
        { picked ->
            selection = picked
            val range = picked?.toTextRange()
            onExcerpt(token, range?.let { renderedText.substring(it.min, it.max) })
        },
        content,
    )
}

@Composable
private fun LinkButton(link: AnswerLink) {
    val uriHandler = LocalUriHandler.current
    // The same shape goes to the face and to the press: the extruded edge under a
    // solid control is cut from the control's own outline.
    val shape = RoundedCornerShape(12.dp)
    Button(
        onClick = { runCatching { uriHandler.openUri(link.url) } },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AnswerStyle.buttonHeight)
            .tactile(depth = AnswerStyle.keyDepth, shape = shape),
        shape = shape,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                text = link.label ?: hostOf(link.url),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = trimUrl(link.url) + "  ↗",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DiffBlock(block: AnswerBlock.Diff, onExcerpt: (Any, String?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        block.path?.let { path ->
            Text(
                text = "$path  +${block.added} −${block.removed}",
                fontSize = AnswerStyle.codeSize,
                color = scheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
        ) {
            block.lines.forEach { line ->
                val background = when (line.kind) {
                    DiffLine.Kind.ADD -> AnswerStyle.diffAdd(isDark)
                    DiffLine.Kind.DEL -> AnswerStyle.diffDel(isDark)
                    DiffLine.Kind.CONTEXT -> Color.Transparent
                }
                SelectableRun(line.text, onExcerpt) {
                    Text(
                        text = line.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = AnswerStyle.codeSize,
                        lineHeight = AnswerStyle.codeSize * 1.5,
                        color = scheme.onBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(background)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TableBlock(block: AnswerBlock.Table, onExcerpt: (Any, String?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            block.header.forEach { cell ->
                SelectableRun(cell, onExcerpt) {
                    Text(
                        text = cell,
                        fontSize = AnswerStyle.codeSize,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onBackground,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                }
            }
        }
        block.rows.forEachIndexed { index, row ->
            if (index > 0) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                row.forEach { cell ->
                    SelectableRun(cell, onExcerpt) {
                        Text(
                            text = cell,
                            fontSize = AnswerStyle.codeSize,
                            color = scheme.onBackground,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** How long one sweep of a working indicator takes, in both places that show one. */
internal const val SWEEP_PERIOD_MS = 1300

/**
 * A value that rises from 0 to 1 over [periodMillis] and starts again, for as
 * long as this is composed.
 *
 * It is advanced by the frame clock directly rather than by an animation spec.
 * The frames this screen already gets for its press animations are enough to
 * move the value, so the sweep cannot be left standing by an animation the
 * runtime decides not to run.
 */
@Composable
internal fun rememberSweep(periodMillis: Int): State<Float> {
    val progress = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMillis) {
        val period = periodMillis * 1_000_000L
        var start = 0L
        withFrameNanos { start = it }
        while (true) {
            withFrameNanos { now -> progress.floatValue = ((now - start) % period) / period.toFloat() }
        }
    }
    return progress
}

/**
 * The only working indicator: an animated bar. The chosen design has no status
 * text, so this carries the "pi is busy" signal on its own.
 */
@Composable
fun WorkingShimmer(modifier: Modifier = Modifier) {
    val progress by rememberSweep(SWEEP_PERIOD_MS)
    val scheme = MaterialTheme.colorScheme
    val travel = 120f
    val start = progress * travel - travel
    Box(
        modifier = modifier
            .width(64.dp)
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(scheme.surfaceVariant, scheme.primary, scheme.surfaceVariant),
                    startX = start,
                    endX = start + travel,
                ),
            ),
    )
}

private val inlineRegex = Regex(
    """(\*\*[^*\n]+\*\*|`[^`\n]+`|\[[^\]\n]+\]\([^)\s]+\)|https?://[^\s<>()\[\]{}"']+)""",
)

/** Bold, inline code chips, markdown links and bare URLs. */
private fun inlineText(raw: String, linkColor: Color, chipColor: Color): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        for (match in inlineRegex.findAll(raw)) {
            if (match.range.first > cursor) {
                append(raw.substring(cursor, match.range.first))
            }
            val token = match.value
            when {
                token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(token.removeSurrounding("**"))
                }

                token.startsWith("`") -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = AnswerStyle.bodySize * AnswerStyle.inlineCodeScale,
                        background = chipColor,
                    ),
                ) { append(token.removeSurrounding("`")) }

                token.startsWith("[") && token.contains("](") -> {
                    val label = token.substringAfter('[').substringBefore("](")
                    val url = token.substringAfter("](").removeSuffix(")")
                    appendLink(label, url, linkColor)
                }

                else -> {
                    val url = token.trimEnd('.', ',', ';', ':', '!', '?', ')')
                    val trailing = token.removePrefix(url)
                    appendLink(url, url, linkColor)
                    if (trailing.isNotEmpty()) append(trailing)
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < raw.length) append(raw.substring(cursor))
    }

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendLink(
    label: String,
    url: String,
    linkColor: Color,
) {
    withLink(LinkAnnotation.Url(url)) {
        withStyle(
            SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
        ) { append(label) }
    }
}
