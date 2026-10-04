package com.maverock24.pimobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * Renders an answer as text with working links.
 *
 * Answers are plain text as far as this app is concerned, but they routinely
 * cite URLs, and those have to be tappable: the point of reading an answer on the
 * phone is often to go and confirm it at the source. Light markdown (bold,
 * inline code, headings) is folded into the same string.
 */
object ResultFormat {

    private val tokenRegex = Regex("""(\*\*[^*\n]+\*\*|`[^`\n]+`|https?://[^\s<>()\[\]{}"']+)""")

    fun toAnnotatedString(answer: String, linkColor: Color): AnnotatedString =
        buildAnnotatedString {
            // Matches must be found in the same string we slice, so normalise once.
            val text = normaliseHeadings(answer)
            var cursor = 0
            for (match in tokenRegex.findAll(text)) {
                if (match.range.first > cursor) {
                    append(text.substring(cursor, match.range.first))
                }
                val token = match.value
                when {
                    token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(token.removeSurrounding("**"))
                    }

                    token.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(token.removeSurrounding("`"))
                    }

                    else -> {
                        // Keep trailing punctuation out of the link target.
                        val url = token.trimEnd('.', ',', ';', ':', '!', '?', ')')
                        val trailing = token.removePrefix(url)
                        withLink(LinkAnnotation.Url(url)) {
                            withStyle(
                                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                            ) { append(url) }
                        }
                        if (trailing.isNotEmpty()) append(trailing)
                    }
                }
                cursor = match.range.last + 1
            }
            if (cursor < text.length) append(text.substring(cursor))
        }

    /** "## Heading" becomes bold text instead of showing the hashes. */
    private fun normaliseHeadings(answer: String): String =
        answer.lineSequence()
            .joinToString("\n") { line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("#")) {
                    val text = trimmed.trimStart('#').trim()
                    if (text.isEmpty()) line else "**$text**"
                } else {
                    line
                }
            }

    /** The first URL in an answer, if any: used to offer a direct action. */
    fun firstUrl(answer: String): String? =
        tokenRegex.find(answer)?.value?.takeIf { it.startsWith("http") }
}
