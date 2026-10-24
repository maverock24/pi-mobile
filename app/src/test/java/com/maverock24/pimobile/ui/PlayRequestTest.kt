package com.maverock24.pimobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A spoken request to play an episode, as pure decisions. The rule these cover
 * is the one that cannot be got wrong by accident: a play request has to be the
 * whole utterance and every word of it has to name the file, so a dictated
 * prompt that mentions playing is never turned into audio.
 */
class PlayRequestTest {

    private fun row(
        id: String,
        title: String,
        kind: String = "audio",
        state: String = "ready",
    ) = FileRow(
        id = id,
        name = "$id.mp3",
        title = title,
        kind = kind,
        state = state,
        bytes = 1L,
        durationSeconds = 535.0,
        durationEstimated = false,
        voice = "af_heart",
        createdAt = "2026-10-23T18:13:07.000Z",
        error = null,
    )

    // Newest first, as the bridge sends the library.
    private val library = listOf(
        row("chem-02-why-onions-make-you-cry", "Chemistry: Why onions make you cry, and what actually helps"),
        row("chem-01-why-ice-floats", "Chemistry: Why ice floats when almost everything else sinks"),
        row("bio-01-how-antibiotics-work", "Biology: How antibiotics work"),
        row("bees", "Bees"),
        row("notes.txt", "Notes", kind = "text"),
        row("chem-03-still-rendering", "Chemistry: Still rendering", state = "rendering"),
    )

    @Test
    fun `a play request is the words after a leading play`() {
        assertEquals(listOf("the", "chemistry", "one"), playPhrase("Play the chemistry one."))
        assertEquals(listOf("chemistry"), playPhrase("please PLAY  chemistry!"))
        assertEquals(emptyList<String>(), playPhrase("Play."))
    }

    @Test
    fun `words that do not start with play are not a play request`() {
        // The two that would be dangerous: a prompt about playing, and a word
        // that merely contains "play".
        assertNull(playPhrase("I want you to play with the timeline"))
        assertNull(playPhrase("replay the chemistry one"))
        assertNull(playPhrase("send it now"))
        assertNull(playPhrase(""))
    }

    @Test
    fun `naming words drop the filler and keep the words that choose a file`() {
        assertEquals(listOf("chemistry"), playKeywords(playPhrase("play the chemistry one")!!))
        assertEquals(listOf("ice", "floats"), playKeywords(playPhrase("play the one about ice floats")!!))
        assertTrue(playKeywords(playPhrase("play it")!!).isEmpty())
    }

    @Test
    fun `a request naming one episode picks that episode`() {
        val one = spokenFileMatches(playPhrase("play the chemistry one")!!, library)
        assertEquals(2, one.size)
        // Newest first, so the newest match is the first candidate.
        assertEquals("chem-02-why-onions-make-you-cry", one.first().id)

        val floats = spokenFileMatches(playPhrase("play why ice floats")!!, library)
        assertEquals(listOf("chem-01-why-ice-floats"), floats.map { it.id })

        val antibiotics = spokenFileMatches(playPhrase("play antibiotics")!!, library)
        assertEquals(listOf("bio-01-how-antibiotics-work"), antibiotics.map { it.id })

        // A short word is a prefix of the title, which is how a person shortens
        // a name out loud.
        assertEquals(2, spokenFileMatches(playPhrase("play chem")!!, library).size)
    }

    @Test
    fun `a word that names no file matches nothing rather than the first file`() {
        assertTrue(spokenFileMatches(playPhrase("play the timeline one")!!, library).isEmpty())
        // Every word has to name the file, so a longer sentence that happens to
        // mention a real episode matches nothing and stays dictated text.
        assertTrue(spokenFileMatches(playPhrase("play the chemistry one and summarise")!!, library).isEmpty())
        assertTrue(spokenFileMatches(playPhrase("play it")!!, library).isEmpty())
    }

    @Test
    fun `only a ready audio or video row can be played`() {
        // A text row answers to its own name but is not something to play, and a
        // talk that is still rendering has no bytes to play yet.
        assertTrue(spokenFileMatches(playPhrase("play notes")!!, library).isEmpty())
        assertTrue(spokenFileMatches(playPhrase("play still rendering")!!, library).isEmpty())
    }
}
