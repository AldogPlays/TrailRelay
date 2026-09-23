package com.trailrelay.app.offline

import android.content.Context
import android.util.Log
import com.trailrelay.app.map.AERIAL_STYLE_URI
import com.trailrelay.app.trails.Trail
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.*

/** Main-thread owner using only application context. Activities subscribe; they never own downloads. */
class OfflineDownloads private constructor(context: Context) {
    class Package(val region: OfflineRegion, val metadata: OfflineMetadata?) {
        var status: OfflineRegionStatus? = null
        var active = false
        var deleting = false
        var error: String? = null
        var stateRevision = 0
        val complete get() = status?.let {
            it.isComplete && offlineComplete(it.completedResourceCount, it.requiredResourceCount, it.isRequiredResourceCountPrecise)
        } == true
        val libraryState get() = offlineLibraryState(status != null, complete,
            status?.downloadState == OfflineRegion.STATE_ACTIVE, active, error != null)
    }

    private val appContext = context.applicationContext
    private val manager = OfflineManager.getInstance(appContext)
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
    fun packageFor(id: String): Package? = packages.filter { it.metadata?.belongsTo(id) == true }
        .sortedWith(compareByDescending<Package> { it.complete }.thenByDescending { it.metadata?.createdAt ?: 0L })
        .firstOrNull()
    fun isCreating(id: String) = id in creating
    fun errorFor(id: String) = errors[id]
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
                    val definition = region.definition as? OfflineTilePyramidRegionDefinition
                    if (metadata == null || definition?.styleURL != AERIAL_STYLE_URI ||
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
            override fun onStatusChanged(status: OfflineRegionStatus) = receiveStatus(item, status)
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

    private fun queryStatus(item: Package, expectActive: Boolean = false) {
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
                if (expectActive && !item.complete && status.downloadState != OfflineRegion.STATE_ACTIVE) {
                    fail(item, "Download did not start. Tap Resume Download to try again.", "Region stayed inactive after activation")
                }
            }
            override fun onError(error: String?) {
                if (revision != item.stateRevision) return
                fail(item, "Unable to read download progress. Reopen this screen to retry.", error ?: "Unknown status error")
            }
        })
    }

    private fun receiveStatus(item: Package, status: OfflineRegionStatus) {
        if (item !in packages || item.deleting) return
        val wasComplete = item.complete
        item.status = status
        if (!item.complete && item.error == null) item.active = status.downloadState == OfflineRegion.STATE_ACTIVE
        if (item.complete) {
            if (!wasComplete) Log.i(TAG, "Complete: trail ${item.metadata?.trailId}, region ${item.region.id}, " +
                "${status.completedTileCount} tiles, ${status.completedResourceSize} bytes")
            item.error = null
            if (item.active || status.downloadState == OfflineRegion.STATE_ACTIVE) {
                item.active = false
                item.region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }
        }
        changed()
    }

    private fun fail(item: Package, message: String, technical: String) {
        if (item !in packages || item.deleting) return
        Log.e(TAG, "Trail ${item.metadata?.trailId}, region ${item.region.id}: $technical")
        item.error = message
        item.stateRevision++
        item.active = false
        item.region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        changed()
    }

    fun start(trail: Trail) {
        if (!loaded || loadError != null || isCreating(trail.id)) return
        val existing = packageFor(trail.id)
        if (existing != null && (existing.complete || existing.active || existing.deleting || existing.status == null)) return
        errors.remove(trail.id)
        if (existing != null) {
            activate(existing)
            return
        }
        val bounds = try {
            OfflineCoverage.padded(CoverageBounds(trail.minLatitude, trail.minLongitude, trail.maxLatitude, trail.maxLongitude))
                .also {
                    require(!OfflineCoverage.isTooLarge(OfflineCoverage.estimateTiles(it))) {
                        "This trail area is too large for offline maps in this version."
                    }
                }
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Offline preflight rejected trail ${trail.id}", error)
            errors[trail.id] = error.message ?: "Invalid trail area."
            changed()
            return
        }
        creating.add(trail.id)
        changed()
        val definition = OfflineTilePyramidRegionDefinition(AERIAL_STYLE_URI,
            LatLngBounds.from(bounds.north, bounds.east, bounds.south, bounds.west),
            OfflineCoverage.MIN_ZOOM.toDouble(), OfflineCoverage.MAX_ZOOM.toDouble(), 1f)
        // The bundled raster URL has no @2x variant. Pixel ratio 1 keeps resource identity stable.
        val metadata = OfflineMetadata(trail.id, trail.name, System.currentTimeMillis())
        try {
            manager.createOfflineRegion(definition, metadata.encode(), object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    creating.remove(trail.id)
                    val item = packages.firstOrNull { it.region.id == offlineRegion.id } ?: attach(offlineRegion, metadata)
                    activate(item)
                }
                override fun onError(error: String) = creationFailed(trail.id, error)
            })
        } catch (error: RuntimeException) {
            creationFailed(trail.id, error.toString())
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
        try {
            activateOfflineRegion(item.complete,
                readAsset = { path -> appContext.assets.open(path).use { it.readBytes() } },
                cacheResource = { uri, bytes ->
                    manager.putResourceWithUrl(uri, bytes, 0, 0, null, false)
                    Log.d(TAG, "Bundled style queued in MapLibre cache: $uri, ${bytes.size} bytes")
                },
                activate = {
                    item.region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    item.active = true
                    Log.i(TAG, "Requested STATE_ACTIVE: trail ${item.metadata?.trailId}, region ${item.region.id}")
                },
                requestStatus = { queryStatus(item, expectActive = true) })
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
