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
        refresh()
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
                    store.get(localId ?: remote?.let(::communityTrailId).orEmpty())
                }
                saved to catalogEntry
            }
            post {
                loading = false
                result.onSuccess { (saved, catalogEntry) ->
                    trail = saved
                    entry = catalogEntry
                    if (saved == null && catalogEntry == null) message = "This trail is no longer available. Return to the list and try again."
                }.onFailure { message = "Unable to load trail details. Try reopening this trail." }
            }
        }
    }

    fun download() {
        val selected = entry ?: return
        if (downloading || trail != null) return
        downloading = true
        message = null
        onChange?.invoke()
        worker.execute {
            val result = runCatching {
                TrailStore(getApplication()).use { store ->
                    // Recheck persisted identity before any network request or insert.
                    store.get(communityTrailId(selected.id)) ?: store.download(selected)
                }
            }
            post {
                downloading = false
                result.onSuccess { trail = it; message = "Saved in My Trails." }
                    .onFailure { message = "Download failed: ${it.message ?: "Check your connection and storage space."}" }
            }
        }
    }

    private fun post(action: () -> Unit) = main.post { if (!cleared) { action(); onChange?.invoke() } }
    override fun onCleared() { cleared = true; onChange = null; worker.shutdown() }
}
