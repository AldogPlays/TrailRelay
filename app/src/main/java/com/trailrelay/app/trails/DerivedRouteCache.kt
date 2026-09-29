package com.trailrelay.app.trails

import java.io.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

/** Disposable binary GpxTrack snapshots, never a substitute for an existing canonical GPX.
 * Stored under filesDir to survive normal Android cache eviction. No selected-only analysis.
 * All calls belong on workers. Writes are atomic; failures never fail a canonical operation.
 */
class DerivedRouteCache(
    private val root: File,
    private val schema: Int = 1,
    private val report: (String, Long, String) -> Unit = { _, _, _ -> },
) {
    private val directory = File(root, "derived-routes")
    private val hits = AtomicInteger()
    private val misses = AtomicInteger()
    private val reparsed = AtomicInteger()
    fun summary() = "hits=${hits.get()} misses=${misses.get()} reparsed=${reparsed.get()} scope=activity"
    private data class Stamp(val size: Long, val modified: Long)
    private fun stamp(file: File) = if (file.isFile) Stamp(file.length(), file.lastModified()) else null
    // Only the short ID is hashed for a safe filename; canonical GPX bytes are never hashed here.
    internal fun entry(id: String) = File(directory, MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".route")

    fun load(trail: Trail, parse: (File) -> GpxTrack): GpxTrack {
        val file = File(root, trail.gpxLocalPath)
        val identity = stamp(file)
        val cached = entry(trail.id)
        val started = System.nanoTime()
        if (cached.isFile) {
            try {
                require(identity != null)
                require(cached.length() in 1..MAX_BYTES.toLong())
                val bytes = cached.readBytes()
                val track = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val checksum = input.readLong()
                    require(CRC32().apply { update(bytes, 8, bytes.size - 8) }.value == checksum)
                    require(input.readInt() == MAGIC && input.readInt() == schema)
                    require(input.text() == trail.id && input.text() == trail.gpxLocalPath)
                    require(input.readLong() == identity.size && input.readLong() == identity.modified)
                    val name = input.text(); val description = input.text()
                    val count = input.readInt().also { require(it in 1..250_000) }
                    var total = 0
                    val segments = List(count) {
                        val size = input.readInt().also { require(it in 0..250_000 - total) }
                        total += size
                        List(size) {
                            val lat = input.readDouble(); val lon = input.readDouble()
                            val ele = if (input.readBoolean()) input.readDouble() else null
                            require(lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0)
                            require(ele == null || ele.isFinite())
                            TrackPoint(lat, lon, ele)
                        }
                    }
                    require(segments.any { it.size >= 2 } && input.read() == -1)
                    GpxTrack(name, description, segments)
                }
                require(stamp(file) == identity)
                hits.incrementAndGet()
                report("route_cache_read", started, "success=true")
                report("route_cache_hit", started, "")
                return track
            } catch (_: Exception) {
                synchronized(lock) { cached.delete() }
                report("route_cache_read", started, "success=false")
                report("route_cache_invalid", started, "")
            }
        }
        misses.incrementAndGet()
        report("route_cache_miss", started, "")
        reparsed.incrementAndGet()
        val track = parse(file)
        // A replacement/removal during parsing must not acquire an old geometry snapshot.
        if (identity != null && stamp(file) == identity) write(trail, track, identity)
        return track
    }

    fun write(trail: Trail, track: GpxTrack) {
        stamp(File(root, trail.gpxLocalPath))?.let { write(trail, track, it) }
    }

    private fun write(trail: Trail, track: GpxTrack, identity: Stamp) {
        val started = System.nanoTime()
        var temporary: File? = null
        val success = runCatching {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC); output.writeInt(schema)
                output.text(trail.id); output.text(trail.gpxLocalPath)
                output.writeLong(identity.size); output.writeLong(identity.modified)
                output.text(track.name); output.text(track.description)
                output.writeInt(track.segments.size)
                track.segments.forEach { segment ->
                    output.writeInt(segment.size)
                    segment.forEach { point ->
                        output.writeDouble(point.latitude); output.writeDouble(point.longitude)
                        output.writeBoolean(point.elevation != null)
                        point.elevation?.let(output::writeDouble)
                    }
                }
            }
            val bytes = buffer.toByteArray()
            require(bytes.size + 8 <= MAX_BYTES)
            synchronized(lock) {
                check(stamp(File(root, trail.gpxLocalPath)) == identity)
                check(directory.isDirectory || directory.mkdirs())
                val staged = File.createTempFile("route-", ".tmp", directory)
                temporary = staged
                DataOutputStream(staged.outputStream().buffered()).use {
                    it.writeLong(CRC32().apply { update(bytes) }.value); it.write(bytes)
                }
                check(staged.renameTo(entry(trail.id)))
            }
        }.isSuccess
        temporary?.delete()
        report("route_cache_write", started, "success=$success")
    }

    fun remove(id: String) { runCatching { synchronized(lock) { entry(id).delete() } } }

    private fun DataOutputStream.text(value: String?) {
        val bytes = value?.toByteArray(Charsets.UTF_8)
        writeInt(bytes?.size ?: -1)
        bytes?.let { write(it) }
    }
    private fun DataInputStream.text(): String? {
        val size = readInt()
        if (size == -1) return null
        require(size in 0..262_144 && size <= available())
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }
    companion object {
        private const val MAGIC = 0x54524743
        private const val MAX_BYTES = 16 * 1024 * 1024
        private val lock = Any()
    }
}
