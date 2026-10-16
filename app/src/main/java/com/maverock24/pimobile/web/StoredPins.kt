package com.maverock24.pimobile.web

import com.maverock24.pimobile.data.PinsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The [WebPins] surface over the phone's own [PinsStore].
 *
 * The store is one small JSON document read and rewritten whole, so each call
 * runs on the IO dispatcher rather than the request thread. This is the same file
 * the app's own Pins screen reads, so a pin renamed or deleted from the browser
 * is the phone's pin and not a second copy.
 */
internal class StoredPins(private val store: PinsStore) : WebPins {

    override suspend fun list(): List<WebPin> = withContext(Dispatchers.IO) {
        store.load().map { WebPin(id = it.id, title = it.title, prompt = it.prompt, answer = it.answer) }
    }

    override suspend fun rename(id: String, title: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return@withContext false
        val pins = store.load().toMutableList()
        val index = pins.indexOfFirst { it.id == id }
        if (index < 0) return@withContext false
        pins[index] = pins[index].copy(title = trimmed)
        store.save(pins)
        true
    }

    override suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val pins = store.load().toMutableList()
        val removed = pins.removeAll { it.id == id }
        if (removed) store.save(pins)
        removed
    }
}
