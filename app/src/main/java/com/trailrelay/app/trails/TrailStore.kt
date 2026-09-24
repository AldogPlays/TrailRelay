package com.trailrelay.app.trails

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import com.trailrelay.app.trails.community.CatalogEntry
import com.trailrelay.app.trails.community.CommunityClient
import com.trailrelay.app.trails.community.communityTrailId
import com.trailrelay.app.trails.community.toLocalTrail
import com.trailrelay.app.trails.community.resolveCommunityDownload

/** Call on a worker thread. GPX bytes are staged privately, validated, then published atomically. */
class TrailStore(private val context: Context) : SQLiteOpenHelper(context, "trails.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE trails (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                description TEXT,
                source TEXT NOT NULL CHECK(source IN ('IMPORTED', 'COMMUNITY')),
                gpx_local_path TEXT NOT NULL UNIQUE,
                distance_meters REAL NOT NULL,
                min_latitude REAL NOT NULL,
                min_longitude REAL NOT NULL,
                max_latitude REAL NOT NULL,
                max_longitude REAL NOT NULL,
                imported_at INTEGER NOT NULL,
                remote_id TEXT,
                UNIQUE(source, remote_id)
            )
        """.trimIndent())
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE trails ADD COLUMN remote_id TEXT")
            db.execSQL("CREATE UNIQUE INDEX trail_remote_identity ON trails(source, remote_id)")
        }
    }

    fun list(): List<Trail> = readableDatabase.query("trails", null, null, null, null, null,
        "imported_at DESC, name COLLATE NOCASE ASC").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.trail()) }
    }

    fun get(id: String): Trail? = readableDatabase.query("trails", null, "id = ?",
        arrayOf(id), null, null, null).use { if (it.moveToFirst()) it.trail() else null }

    fun load(trail: Trail): GpxTrack = File(context.filesDir, trail.gpxLocalPath)
        .inputStream().use(GpxParser::parse)

    fun hasLocalGpx(trail: Trail): Boolean = File(context.filesDir, trail.gpxLocalPath).isFile

    fun localGpxSize(trail: Trail): Long? = File(context.filesDir, trail.gpxLocalPath)
        .takeIf(File::isFile)?.length()

    /** Removes this local route record and file only; associated MapLibre imagery is independent. */
    fun removeLocalRoute(trail: Trail): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (db.delete("trails", "id = ?", arrayOf(trail.id)) != 1) return false
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        File(context.filesDir, trail.gpxLocalPath).delete()
        return true
    }

    fun import(uri: Uri): Trail {
        val directory = File(context.filesDir, "trails")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create trail storage.")
        val staged = File.createTempFile("import-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 32L * 1024 * 1024) throw GpxException("GPX exceeds the 32 MB import limit.")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: throw IOException("Cannot open the selected document.")
            val track = staged.inputStream().use(GpxParser::parse)
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val existing = get(id)
            val relativePath = "trails/$id.gpx"
            val destination = File(context.filesDir, relativePath)
            val points = track.segments.flatten()
            val trail = existing ?: Trail(id, track.name ?: documentName(uri), track.description,
                TrailSource.IMPORTED, relativePath, track.distanceMeters,
                points.minOf { it.latitude }, points.minOf { it.longitude },
                points.maxOf { it.latitude }, points.maxOf { it.longitude }, System.currentTimeMillis())
            // Replacing with identical validated bytes also repairs a missing private file.
            if (!staged.renameTo(destination)) throw IOException("Cannot save the GPX file.")
            if (existing == null) {
                try {
                    writableDatabase.insertOrThrow("trails", null, trail.values())
                } catch (error: Exception) {
                    destination.delete()
                    throw error
                }
            }
            return trail
        } finally {
            staged.delete()
        }
    }

    fun download(entry: CatalogEntry): Trail {
        val directory = File(context.filesDir, "trails")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create trail storage.")
        val staged = File.createTempFile("community-", ".tmp", directory)
        var published: File? = null
        try {
            staged.outputStream().use {
                CommunityClient.download(entry.gpxUrl, it)
                it.fd.sync()
            }
            val track = staged.inputStream().use(GpxParser::parse)
            val id = communityTrailId(entry.id)
            val existing = get(id)
            // A new filename keeps the previous GPX intact until SQLite commits the replacement.
            val relativePath = "trails/$id-${java.util.UUID.randomUUID()}.gpx"
            val destination = File(context.filesDir, relativePath)
            val trail = entry.toLocalTrail(track, relativePath, System.currentTimeMillis())
            if (!staged.renameTo(destination)) throw IOException("Cannot save the GPX file.")
            published = destination
            val db = writableDatabase
            db.beginTransaction()
            try {
                if (existing == null) db.insertOrThrow("trails", null, trail.values())
                else check(db.update("trails", trail.values(), "id = ? AND source = ? AND remote_id = ?",
                    arrayOf(id, TrailSource.COMMUNITY.name, entry.id)) == 1)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            published = null
            existing?.let { File(context.filesDir, it.gpxLocalPath).delete() }
            return trail
        } finally {
            staged.delete()
            published?.delete()
        }
    }

    /** Community identity is stable across preview and detail entry points. */
    fun downloadIfMissing(entry: CatalogEntry): Trail {
        val existing = get(communityTrailId(entry.id))
        return resolveCommunityDownload(existing, ::hasLocalGpx) { download(entry) }
    }

    private fun Trail.values() = ContentValues().apply {
        put("id", id); put("name", name); put("description", description)
        put("source", source.name); put("gpx_local_path", gpxLocalPath)
        put("distance_meters", distanceMeters)
        put("min_latitude", minLatitude); put("min_longitude", minLongitude)
        put("max_latitude", maxLatitude); put("max_longitude", maxLongitude)
        put("imported_at", importedAt); put("remote_id", remoteId)
    }

    private fun documentName(uri: Uri): String {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        return name?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() } ?: "Imported trail"
    }

    private fun Cursor.trail() = Trail(
        getString(getColumnIndexOrThrow("id")), getString(getColumnIndexOrThrow("name")),
        getString(getColumnIndexOrThrow("description")),
        TrailSource.valueOf(getString(getColumnIndexOrThrow("source"))),
        getString(getColumnIndexOrThrow("gpx_local_path")),
        getDouble(getColumnIndexOrThrow("distance_meters")),
        getDouble(getColumnIndexOrThrow("min_latitude")), getDouble(getColumnIndexOrThrow("min_longitude")),
        getDouble(getColumnIndexOrThrow("max_latitude")), getDouble(getColumnIndexOrThrow("max_longitude")),
        getLong(getColumnIndexOrThrow("imported_at")),
        getString(getColumnIndexOrThrow("remote_id")),
    )
}
