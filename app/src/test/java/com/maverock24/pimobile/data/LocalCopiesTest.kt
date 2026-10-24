package com.maverock24.pimobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The clear in Settings is the only thing in the app that removes more than one
 * file at a time, and there is no undo, so its boundary is what these cover: it
 * touches the files directly inside the directory it is given, and nothing else.
 * The directory beside it stands in for the bridge's library, which is on the
 * laptop and which no code here can name.
 */
class LocalCopiesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(dir: File, name: String, bytes: Int): File =
        File(dir, name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun clearDeletesTheFilesInTheDirectoryAndReportsTheirBytes() {
        val downloads = temp.newFolder("talks")
        file(downloads, "chem-01.mp3", 100)
        file(downloads, "chem-01.mp3.json", 20)

        val freed = clearDirectory(downloads)

        assertEquals(120L, freed)
        assertEquals(0, downloads.listFiles()!!.size)
    }

    @Test
    fun clearLeavesADirectoryInsideTheDirectoryAlone() {
        val downloads = temp.newFolder("talks")
        val sub = File(downloads, "scripts").apply { mkdirs() }
        val inside = file(sub, "draft.txt", 50)
        // An empty subdirectory is the case a clear that deletes every entry it
        // sees would take, so it is here too.
        val empty = File(downloads, "empty").apply { mkdirs() }

        val freed = clearDirectory(downloads)

        assertEquals(0L, freed)
        assertTrue("a subdirectory is not something a clear follows", sub.isDirectory)
        assertTrue("an empty subdirectory is left too", empty.isDirectory)
        assertTrue("nor does it empty one", inside.isFile)
        assertTrue("nor does it empty one", inside.readBytes().size == 50)
    }

    @Test
    fun clearDoesNotReachTheDirectoryBesideIt() {
        val downloads = temp.newFolder("talks")
        val library = temp.newFolder("library")
        file(downloads, "bio-01.mp3", 10)
        val master = file(library, "bio-01.mp3", 10)

        clearDirectory(downloads)

        assertTrue("the copy that stands in for the laptop's file is untouched", master.isFile)
        assertEquals(10L, master.length())
    }

    @Test
    fun clearOnAMissingDirectoryFreesNothing() {
        assertEquals(0L, clearDirectory(File(temp.root, "not-there")))
    }

    @Test
    fun bytesCountFilesAndNotTheDirectoryBesideThem() {
        val downloads = temp.newFolder("talks")
        file(downloads, "phys-01.mp3", 300)
        file(downloads, "phys-01.mp3.json", 30)
        val sub = File(downloads, "scripts").apply { mkdirs() }
        file(sub, "draft.txt", 400)

        assertEquals(330L, directoryBytes(downloads))
        assertEquals(0L, directoryBytes(File(temp.root, "not-there")))
    }
}
