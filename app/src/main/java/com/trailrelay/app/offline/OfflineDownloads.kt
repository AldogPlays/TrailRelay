package com.trailrelay.app.offline

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.trailrelay.app.map.MapMode
import com.trailrelay.app.trails.Trail
import com.trailrelay.app.trails.TrailStore
import org.maplibre.android.offline.*
import java.util.concurrent.Executors

/** Main-thread owner using only application context. Activities subscribe; they never own downloads. */
class OfflineDownloads private constructor(context: Context) {
    class Package(val region: OfflineRegion, val metadata: OfflineMetadata?) {
        var status: OfflineRegionStatus? = null
        var active = false
        var deleting = false
        var error: String? = null
        var stateRevision = 0
        var activationPending = false
        val complete get() = status?.let {
            it.isComplete && offlineComplete(it.completedResourceCount, it.requiredResourceCount, it.isRequiredResourceCountPrecise)
        } == true
        val libraryState get() = offlineLibraryState(status != null, complete,
            status?.downloadState == OfflineRegion.STATE_ACTIVE, active, error != null)
    }

    private val appContext = context.applicationContext
    private val debugLogging = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val manager = OfflineManager.getInstance(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prepareWorker = Executors.newSingleThreadExecutor()
    private val packages = mutableListOf<Package>()
    private val creating = mutableSetOf<String>()
    private val errors = mutableMapOf<String, String>()
    val listeners = mutableSetOf<() -> Unit>()
    var loaded = false
        private set
    var loadError: String? = null
        private set
    private var loading = false

    init { reload() }

    fun allPackages(): List<Package> = packages.sortedWith(compareByDescending<Package> { it.metadata?.createdAt ?: 0L }
        .thenBy { it.region.id })
    fun packageFor(id: String, mode: MapMode): Package? = packages.filter { it.metadata?.belongsTo(id, mode) == true }
        .sortedWith(compareByDescending<Package> { it.complete }.thenByDescending { it.metadata?.createdAt ?: 0L })
        .firstOrNull()
    fun isCreating(id: String, mode: MapMode) = key(id, mode) in creating
    fun errorFor(id: String, mode: MapMode) = errors[key(id, mode)]
    private fun key(id: String, mode: MapMode) = "$id:${mode.id}"
    private fun changed() = listeners.toList().forEach { it() }

    fun reload() {
        if (loading) return
        loading = true
        loadError = null
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val presentIds = offlineRegions.orEmpty().map { it.id }.toSet()
                val staleIds = staleRegionIds(packages.map { it.region.id }.toSet(), presentIds)
                packages.removeAll { it.region.id in staleIds && !it.deleting }
                offlineRegions.orEmpty().forEach { region ->
                    val existing = packages.firstOrNull { it.region.id == region.id }
                    if (existing != null) {
                        if (!existing.deleting) queryStatus(existing)
                        return@forEach
                    }
                    val metadata = OfflineMetadata.decode(region.metadata)
                    val definition = region.definition
                    if (metadata == null || definition !is OfflineTilePyramidRegionDefinition &&
                        definition !is OfflineGeometryRegionDefinition ||
                        definition.styleURL != metadata.mapType.styleUri ||
                        definition.minZoom != metadata.minZoom.toDouble() ||
                        definition.maxZoom != metadata.maxZoom.toDouble()) {
                        Log.w(TAG, "Unrecognized region ${region.id}: missing, incompatible or corrupt metadata/definition")
                        queryStatus(attach(region, null))
                    } else queryStatus(attach(region, metadata))
                }
                loading = false
                loaded = true
                changed()
            }
            override fun onError(error: String) {
                Log.e(TAG, "Listing offline regions: $error")
                loading = false
                loadError = "Unable to read saved offline maps. Try again."
                changed()
            }
        })
    }

    /** Query persistent native status again when returning to the screen. */
    fun refresh() = reload()

    private fun attach(region: OfflineRegion, metadata: OfflineMetadata?): Package {
        val item = Package(region, metadata)
        packages.add(item)
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) = receiveStatus(item, status, fromObserver = true)
            override fun onError(error: OfflineRegionError) {
                fail(item, "Download stopped. Check your connection and free storage, then resume.",
                    "${error.reason}: ${error.message}")
            }
            override fun mapboxTileCountLimitExceeded(limit: Long) {
                fail(item, "Offline tile limit reached. Remove an offline map before resuming.", "Tile limit: $limit")
            }
        })
        Log.d(TAG, "Observer attached: region ${region.id}")
        return item
    }

    private fun queryStatus(item: Package) {
        val revision = item.stateRevision
        item.region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) {
                if (revision != item.stateRevision || item !in packages || item.deleting) return
                if (status == null) {
                    onError("Native status was null")
                    return
                }
                Log.d(TAG, "Status: region ${item.region.id}, nativeState=${status.downloadState}, " +
                    "tiles=${status.completedTileCount}, bytes=${status.completedResourceSize}, " +
                    "resources=${status.completedResourceCount}/${status.requiredResourceCount}, " +
                    "precise=${status.isRequiredResourceCountPrecise}")
                receiveStatus(item, status)
            }
            override fun onError(error: String?) {
                if (revision != item.stateRevision) return
                fail(item, "Unable to read download progress. Reopen this screen to retry.", error ?: "Unknown status error")
            }
        })
    }

    private fun receiveStatus(item: Package, status: OfflineRegionStatus, fromObserver: Boolean = false) {
        if (item !in packages || item.deleting) return
        if (debugLogging) Log.d(TAG, "${item.metadata?.mapType?.id ?: "unknown"} region ${item.region.id}: " +
            "state=${status.downloadState}, active=${item.active}, pending=${item.activationPending}, " +
            "resources=${status.completedResourceCount}/${status.requiredResourceCount}")
        // An observer transition supersedes in-flight getStatus responses from before it.
        if (fromObserver) item.stateRevision++
        val wasComplete = item.complete
        item.status = status
        if (!item.complete && item.error == null) {
            if (status.downloadState == OfflineRegion.STATE_ACTIVE) item.activationPending = false
            if (!item.activationPending) item.active = status.downloadState == OfflineRegion.STATE_ACTIVE
        }
        if (item.complete) {
            if (!wasComplete) Log.i(TAG, "Complete: trail ${item.metadata?.trailId}, region ${item.region.id}, " +
                "${status.completedTileCount} tiles, ${status.completedResourceSize} bytes")
            item.error = null
            item.activationPending = false
            if (item.active || status.downloadState == OfflineRegion.STATE_ACTIVE) {
                item.active = false
                item.region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }
        }
        changed()
    }

    private fun fail(item: Package, message: String, technical: String) {
        if (item !in packages || item.deleting) return
        Log.e(TAG, "${item.metadata?.mapType?.id} trail ${item.metadata?.trailId}, " +
            "region ${item.region.id}: $technical")
        item.error = message
        item.stateRevision++
        item.activationPending = false
        item.active = false
        item.region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        changed()
    }

    fun start(trail: Trail, mode: MapMode) {
        val identity = key(trail.id, mode)
        if (!loaded || loadError != null || isCreating(trail.id, mode)) return
        val existing = packageFor(trail.id, mode)
        if (existing != null && (existing.complete || existing.active || existing.deleting || existing.status == null)) return
        errors.remove(identity)
        if (existing != null) {
            activate(existing)
            return
        }
        creating.add(identity)
        changed()
        prepareWorker.execute {
            val prepared = runCatching {
                val track = TrailStore(appContext).use { it.load(trail) }
                OfflineCorridor.build(track)
            }
            mainHandler.post {
                if (identity !in creating) return@post
                prepared.onSuccess { corridor -> createRegion(trail, mode, identity, corridor) }
                    .onFailure { error ->
                        Log.w(TAG, "Offline preflight rejected trail ${trail.id}", error)
                        creating.remove(identity)
                        errors[identity] = if (error is IllegalArgumentException)
                            error.message ?: "Invalid trail area." else
                            "Unable to prepare offline coverage from the saved route."
                        changed()
                    }
            }
        }
    }

    private fun createRegion(trail: Trail, mode: MapMode, identity: String,
        corridor: OfflineCorridor.Result) {
        // One raster source and pixel ratio 1 preserve the exact bundled resource identity.
        val definition = OfflineGeometryRegionDefinition(mode.styleUri, corridor.geometry,
            OfflineCoverage.MIN_ZOOM.toDouble(), OfflineCoverage.MAX_ZOOM.toDouble(), 1f)
        val metadata = OfflineMetadata(trail.id, trail.name, System.currentTimeMillis(), mapType = mode)
        Log.i(TAG, "Creating ${mode.id} corridor for trail ${trail.id}: " +
            "${corridor.samples} samples, up to ${corridor.estimatedTiles} estimated tiles")
        try {
            manager.createOfflineRegion(definition, metadata.encode(), object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    creating.remove(identity)
                    val item = packages.firstOrNull { it.region.id == offlineRegion.id } ?: attach(offlineRegion, metadata)
                    activate(item)
                }
                override fun onError(error: String) = creationFailed(identity, error)
            })
        } catch (error: RuntimeException) {
            creationFailed(identity, error.toString())
        }
    }

    private fun creationFailed(id: String, error: String) {
        Log.e(TAG, "Creating region for $id: $error")
        creating.remove(id)
        errors[id] = "Unable to create offline coverage. Check free storage and try again."
        changed()
    }

    private fun activate(item: Package) {
        if (item !in packages || item.metadata == null || item.complete || item.active || item.deleting) return
        item.error = null
        item.stateRevision++
        item.activationPending = true
        try {
            activateOfflineRegion(item.complete, item.metadata.mapType,
                readAsset = { path -> appContext.assets.open(path).use { it.readBytes() } },
                cacheResource = { uri, bytes ->
                    manager.putResourceWithUrl(uri, bytes, 0, 0, null, false)
                    Log.d(TAG, "Bundled style queued in MapLibre cache: $uri, ${bytes.size} bytes")
                },
                activate = {
                    item.region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    item.active = true
                    Log.i(TAG, "Requested STATE_ACTIVE: ${item.metadata?.mapType?.id} trail " +
                        "${item.metadata?.trailId}, region ${item.region.id}")
                },
                requestStatus = { queryStatus(item) })
        } catch (error: Exception) {
            fail(item, "Unable to start offline download. Tap Resume Download to try again.", error.toString())
        }
        changed()
    }

    fun resume(item: Package) {
        if (canResumeOffline(item.libraryState, item.metadata != null)) activate(item)
    }

    fun pause(item: Package) {
        if (item !in packages || item.deleting) return
        item.stateRevision++
        item.active = false
        item.activationPending = false
        item.region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        queryStatus(item)
        changed()
    }

    fun delete(item: Package) {
        if (item !in packages || item.deleting) return
        pause(item)
        item.deleting = true
        changed()
        item.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                packages.remove(item)
                changed()
            }
            override fun onError(error: String) {
                Log.e(TAG, "Deleting region ${item.region.id}: $error")
                item.deleting = false
                item.error = "Unable to remove offline coverage. Try again."
                changed()
                reload() // A region removed elsewhere may already be absent.
            }
        })
    }

    companion object {
        private const val TAG = "TrailRelayOffline"
        private var instance: OfflineDownloads? = null
        fun get(context: Context): OfflineDownloads = instance ?: OfflineDownloads(context.applicationContext).also { instance = it }
    }
}
