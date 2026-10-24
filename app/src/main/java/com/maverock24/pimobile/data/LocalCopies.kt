package com.maverock24.pimobile.data

import java.io.File

/**
 * The bytes the files directly inside [dir] take, or 0 when [dir] is not there.
 *
 * Only files count: a directory is not part of what a clear would free, because
 * [clearDirectory] does not follow one.
 */
fun directoryBytes(dir: File): Long = (dir.listFiles() ?: emptyArray()).filter { it.isFile }.sumOf { it.length() }

/**
 * Delete the files directly inside [dir] and return the bytes that went.
 *
 * This is the only code that removes more than one thing at a time, and it only
 * ever runs on a directory the app itself owns: the download directory under
 * `filesDir` and the app's `cacheDir`. Two rules keep it there. Every path it
 * deletes is one `listFiles()` returned, so nothing is built from a name the
 * bridge sent, and it never recurses, so a directory inside [dir] is left alone
 * rather than walked out of.
 *
 * There is no path from here to the bridge's library on the laptop or to
 * `~/.pi/agent/talks`: the app downloads over HTTP and never mounts, copies or
 * otherwise names a directory on that machine.
 *
 * A file that will not delete is skipped rather than failing the whole clear,
 * and the bytes it still takes are simply not counted as freed.
 */
fun clearDirectory(dir: File): Long {
    val entries = dir.listFiles() ?: return 0L
    var freed = 0L
    for (entry in entries) {
        if (!entry.isFile) continue
        val size = entry.length()
        if (entry.delete()) freed += size
    }
    return freed
}
