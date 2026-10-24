package com.maverock24.pimobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a car-mode episode row says and whether a tap reaches it. The rule these
 * cover is the one a driver cannot check by looking twice: a row that cannot play
 * has to say why, rather than be a target that does nothing.
 */
class CarEpisodeTest {

    private fun row(
        id: String,
        title: String,
        kind: String = "audio",
        state: String = "ready",
        error: String? = null,
    ) = FileRow(
        id = id,
        name = "$id.mp3",
        title = title,
        kind = kind,
        state = state,
        bytes = 4_220_108L,
        durationSeconds = 566.0,
        durationEstimated = false,
        voice = "af_heart",
        createdAt = "2026-10-23T18:52:16.000Z",
        error = error,
    )

    private fun state(
        row: FileRow,
        downloaded: Boolean = true,
        progress: Float? = null,
        playing: Boolean = false,
        configured: Boolean = true,
    ) = carEpisodeState(
        row = row,
        downloaded = downloaded,
        progress = progress,
        playing = playing,
        isPlayingThis = playing,
        configured = configured,
    )

    @Test
    fun theCategoryIsTheWordsBeforeTheColonInTheTitle() {
        assertEquals("Chemistry", carCategory(row("chem-01", "Chemistry: Why ice floats")))
        assertEquals("Physics", carCategory(row("phys-07", "Physics: How a wing actually makes lift")))
    }

    @Test
    fun aTitleWithNoCategoryReadsAsOther() {
        assertEquals("Other", carCategory(row("bees", "Bees")))
        assertEquals("Other", carCategory(row("odd", "")))
    }

    @Test
    fun aFileOnThePhonePlaysOnOneTap() {
        val state = state(row("chem-01", "Chemistry: Why ice floats"))
        assertTrue(state.enabled)
        assertTrue(state.detail, state.detail.contains("on this phone"))
    }

    @Test
    fun aFileThatIsPlayingOffersThePause() {
        val state = state(row("chem-01", "Chemistry: Why ice floats"), playing = true)
        assertTrue(state.enabled)
        assertTrue(state.detail, state.detail.contains("tap to pause"))
    }

    @Test
    fun aFileThatIsNotOnThePhoneIsDownloadedFromTheBridge() {
        val state = state(row("chem-01", "Chemistry: Why ice floats"), downloaded = false)
        assertTrue(state.enabled)
        assertTrue(state.detail, state.detail.contains("tap to download"))
    }

    @Test
    fun aFileWithNoBridgeToFetchItSaysSoAndCannotBeTapped() {
        val state = state(row("chem-01", "Chemistry: Why ice floats"), downloaded = false, configured = false)
        assertFalse(state.enabled)
        assertTrue(state.detail, state.detail.contains("no bridge is paired"))
    }

    @Test
    fun aDownloadInFlightCannotBeTapped() {
        val state = state(row("chem-01", "Chemistry: Why ice floats"), downloaded = false, progress = 0.42f)
        assertFalse(state.enabled)
        assertEquals("downloading 42%", state.detail)
    }

    @Test
    fun aFailedRenderSaysWhyAndCannotBeTapped() {
        val state = state(row("chem-09", "Chemistry: A broken render", state = "failed", error = "ffmpeg exited 1"))
        assertFalse(state.enabled)
        assertTrue(state.detail, state.detail.contains("render failed"))
        assertTrue(state.detail, state.detail.contains("ffmpeg exited 1"))
    }

    @Test
    fun aRenderStillGoingSaysSoAndCannotBeTapped() {
        val state = state(row("chem-10", "Chemistry: A render in progress", state = "rendering"))
        assertFalse(state.enabled)
        assertTrue(state.detail, state.detail.contains("still rendering"))
    }
}
