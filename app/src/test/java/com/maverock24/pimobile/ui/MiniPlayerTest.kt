package com.maverock24.pimobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mini player as two decisions, apart from the Compose it draws. The rules
 * these cover are the ones a glance cannot check: the bar has to be there while
 * audio plays and has to be gone in car mode, and its progress line has to be a
 * fraction of the file even before the length is known.
 */
class MiniPlayerTest {

    @Test
    fun `shown while the player holds a file`() {
        assertTrue(miniPlayerVisible("bees.mp3", carMode = false))
    }

    @Test
    fun `hidden when the player holds nothing`() {
        assertFalse(miniPlayerVisible(null, carMode = false))
    }

    /**
     * Car mode puts one big play and pause in front of the driver, so a second
     * bar of the same controls there is two places to look at while driving.
     */
    @Test
    fun `hidden in car mode even while audio plays`() {
        assertFalse(miniPlayerVisible("bees.mp3", carMode = true))
    }

    @Test
    fun `progress is a fraction of the file`() {
        assertEquals(0.5f, playbackRatio(30_000L, 60_000L), 0.0001f)
    }

    /** An unknown length is an empty line, not a finished one. */
    @Test
    fun `progress is empty while the length is unknown`() {
        assertEquals(0f, playbackRatio(0L, 0L), 0.0001f)
    }

    /** A position past the reported length is a full line, never an overflow. */
    @Test
    fun `progress never leaves the line`() {
        assertEquals(1f, playbackRatio(90_000L, 60_000L), 0.0001f)
        assertEquals(0f, playbackRatio(-5_000L, 60_000L), 0.0001f)
    }
}
