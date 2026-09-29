package com.trailrelay.app.trails

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** Activity-owned, current-library-only geometry. Two readers let one slow file yield to others.
 * File identity invalidates replacements; Browse and selection share in-flight reads as well.
 * request/retain may run off main and never hold the cache lock while parsing.
 */
class SavedGeometryCache(
    private val root: File,
    private val disk: DerivedRouteCache? = null,
    private val load: (Trail, File) -> GpxTrack = { _, file -> file.inputStream().use(GpxParser::parse) },
) : AutoCloseable {
    private data class Stamp(val path: String, val size: Long, val modified: Long)
    private data class Entry(val stamp: Stamp, val result: CompletableFuture<GpxTrack>)
    private val entries = mutableMapOf<String, Entry>()
    private val readers = Executors.newFixedThreadPool(2)

    @Synchronized fun request(trail: Trail): CompletableFuture<GpxTrack> {
        val file = File(root, trail.gpxLocalPath)
        val stamp = Stamp(trail.gpxLocalPath, file.length(), file.lastModified())
        entries[trail.id]?.takeIf { it.stamp == stamp && !it.result.isCompletedExceptionally }?.let { return it.result }
        val result = CompletableFuture.supplyAsync({
            disk?.load(trail) { load(trail, it) } ?: load(trail, file)
        }, readers)
        entries[trail.id] = Entry(stamp, result)
        return result
    }

    @Synchronized fun retain(ids: Set<String>) {
        entries.keys.retainAll(ids)
    }

    fun diskSummary() = disk?.summary().orEmpty()

    @Synchronized override fun close() {
        entries.values.forEach { it.result.cancel(true) }
        entries.clear()
        readers.shutdownNow()
    }
}
