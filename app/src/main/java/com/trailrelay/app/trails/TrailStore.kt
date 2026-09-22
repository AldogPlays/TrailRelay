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

/** Call on a worker thread. GPX bytes are staged privately, validated, then published atomically. */
class TrailStore(private val context: Context) : SQLiteOpenHelper(context, "trails.db", null, 1) {
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
                imported_at INTEGER NOT NULL
            )
        """.trimIndent())
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No schema migration defined from $oldVersion to $newVersion")
    }

    fun list(): List<Trail> = readableDatabase.query("trails", null, null, null, null, null,
        "imported_at DESC, name COLLATE NOCASE ASC").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.trail()) }
    }

    fun get(id: String): Trail? = readableDatabase.query("trails", null, "id = ?",
        arrayOf(id), null, null, null).use { if (it.moveToFirst()) it.trail() else null }

    fun load(trail: Trail): GpxTrack = File(context.filesDir, trail.gpxLocalPath)
        .inputStream().use(GpxParser::parse)

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
                val values = ContentValues().apply {
                    put("id", trail.id); put("name", trail.name); put("description", trail.description)
                    put("source", trail.source.name); put("gpx_local_path", trail.gpxLocalPath)
                    put("distance_meters", trail.distanceMeters)
                    put("min_latitude", trail.minLatitude); put("min_longitude", trail.minLongitude)
                    put("max_latitude", trail.maxLatitude); put("max_longitude", trail.maxLongitude)
                    put("imported_at", trail.importedAt)
                }
                try {
                    writableDatabase.insertOrThrow("trails", null, values)
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
    )
}
