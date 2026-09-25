package com.trailrelay.app.trails

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import com.trailrelay.app.trails.community.CatalogEntry
import com.trailrelay.app.trails.community.CommunityClient
import com.trailrelay.app.trails.community.communityTrailId
import java.util.concurrent.Executors

/** Keeps a selected trail and an in-flight GPX download through Activity recreation. */
class TrailDetailModel(application: Application) : AndroidViewModel(application) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var cleared = false
    private var initialized = false
    var onChange: (() -> Unit)? = null
    var trail: Trail? = null; private set
    var localUsable = false; private set
    var entry: CatalogEntry? = null; private set
    var loading = true; private set
    var downloading = false; private set
    var message: String? = null; private set
    private var localId: String? = null
    private var remoteId: String? = null

    fun initialize(id: String?, catalogId: String?) {
        if (initialized) return
        initialized = true
        localId = id
        remoteId = catalogId
    }

    fun refresh() {
        if (!initialized || downloading) return
        worker.execute {
            val result = runCatching {
                val catalog = CommunityClient(getApplication()).cached()
                val remote = remoteId ?: localId?.let { id ->
                    TrailStore(getApplication()).use { it.get(id)?.remoteId }
                }
                val catalogEntry = catalog?.firstOrNull { it.id == remote }
                val saved = TrailStore(getApplication()).use { store ->
                    store.get(localId ?: remote?.let(::communityTrailId).orEmpty()).let { trail ->
                        trail to (trail?.let { store.loadUsableGpx(it) != null } == true)
                    }
                }
                saved to catalogEntry
            }
            post {
                loading = false
                result.onSuccess { (saved, catalogEntry) ->
                    trail = saved.first
                    localUsable = saved.second
                    entry = catalogEntry
                    message = when {
                        trail == null && catalogEntry == null -> "This trail is no longer available. Return to the list and try again."
                        trail != null && !localUsable && trail?.source == TrailSource.COMMUNITY && catalogEntry != null ->
                            "Saved GPX is missing or invalid. Download a fresh copy to repair it."
                        trail != null && !localUsable && trail?.source == TrailSource.COMMUNITY ->
                            "Saved GPX is missing or invalid. Open Community to refresh its catalog and repair this route."
                        trail != null && !localUsable -> "Saved GPX is missing or invalid. Reimport this route to repair it."
                        else -> null
                    }
                }.onFailure { message = "Unable to load trail details. Try reopening this trail." }
            }
        }
    }

    fun download() {
        val selected = entry ?: return
        if (downloading || (trail != null && (localUsable || trail?.source != TrailSource.COMMUNITY))) return
        downloading = true
        message = null
        onChange?.invoke()
        worker.execute {
            val result = runCatching {
                TrailStore(getApplication()).use { store ->
                    store.downloadIfMissing(selected)
                }
            }
            post {
                downloading = false
                result.onSuccess { trail = it; localUsable = true; message = "Saved in My Trails." }
                    .onFailure { message = "Download failed: ${it.message ?: "Check your connection and storage space."}" }
            }
        }
    }

    private fun post(action: () -> Unit) = main.post { if (!cleared) { action(); onChange?.invoke() } }
    override fun onCleared() { cleared = true; onChange = null; worker.shutdown() }
}
