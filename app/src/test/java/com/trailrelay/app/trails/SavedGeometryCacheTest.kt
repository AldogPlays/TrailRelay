package com.trailrelay.app.trails

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SavedGeometryCacheTest {
    private val track = GpxTrack(null, null, listOf(listOf(TrackPoint(0.0, 0.0), TrackPoint(0.0, 0.01))))
    private fun trail(id: String, path: String = id) = Trail(id, id, null, TrailSource.IMPORTED,
        path, 100.0, 0.0, 0.0, 1.0, 1.0, 0L)

    @Test fun browseAndSelectionShareOneInFlightParse() {
        val root = Files.createTempDirectory("route-cache").toFile()
        val gate = CountDownLatch(1)
        val count = AtomicInteger()
        try {
            SavedGeometryCache(root) { _, _ -> count.incrementAndGet(); gate.await(5, TimeUnit.SECONDS); track }.use { cache ->
                val browse = cache.request(trail("a"))
                val selection = cache.request(trail("a"))
                assertSame(browse, selection)
                gate.countDown()
                assertSame(track, browse.get(5, TimeUnit.SECONDS))
                assertSame(track, cache.request(trail("a")).get(5, TimeUnit.SECONDS))
                assertEquals(1, count.get())
            }
        } finally { gate.countDown(); root.deleteRecursively() }
    }

    @Test fun oneSlowOrBrokenFileDoesNotBlockAnother() {
        val root = Files.createTempDirectory("route-cache").toFile()
        val gate = CountDownLatch(1)
        try {
            SavedGeometryCache(root) { t, _ ->
                if (t.id == "slow") { gate.await(5, TimeUnit.SECONDS); error("Broken GPX") }
                track
            }.use { cache ->
                val slow = cache.request(trail("slow"))
                val fast = cache.request(trail("fast"))
                assertSame(track, fast.get(2, TimeUnit.SECONDS))
                assertFalse(slow.isDone)
                gate.countDown()
                assertTrue(runCatching { slow.get(5, TimeUnit.SECONDS) }.isFailure)
            }
        } finally { gate.countDown(); root.deleteRecursively() }
    }

    @Test fun replacementAndRemovalInvalidateGeometry() {
        val root = Files.createTempDirectory("route-cache").toFile()
        val count = AtomicInteger()
        try {
            val file = java.io.File(root, "a").apply { writeText("old") }
            SavedGeometryCache(root) { _, _ -> count.incrementAndGet(); track }.use { cache ->
                val initial = cache.request(trail("a")); initial.get(5, TimeUnit.SECONDS)
                file.writeText("replacement with a different size")
                val replaced = cache.request(trail("a")); replaced.get(5, TimeUnit.SECONDS)
                assertNotSame(initial, replaced)
                cache.retain(emptySet())
                assertNotSame(replaced, cache.request(trail("a")).also { it.get(5, TimeUnit.SECONDS) })
                assertEquals(3, count.get())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun obsoleteReadCannotOverwriteAReplacement() {
        val root = Files.createTempDirectory("route-cache").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val replacement = track.copy(name = "replacement")
        try {
            val file = java.io.File(root, "a").apply { writeText("old") }
            SavedGeometryCache(root) { _, _ ->
                if (count.incrementAndGet() == 1) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    track
                } else replacement
            }.use { cache ->
                val old = cache.request(trail("a"))
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                file.writeText("new file contents")
                val current = cache.request(trail("a"))
                assertSame(replacement, current.get(2, TimeUnit.SECONDS))
                release.countDown()
                old.get(5, TimeUnit.SECONDS)
                assertSame(current, cache.request(trail("a")))
            }
        } finally { release.countDown(); root.deleteRecursively() }
    }
}
