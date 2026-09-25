package com.trailrelay.app.trails.community

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import com.trailrelay.app.trails.TrailStore
import java.util.concurrent.Executors

class CommunityModel(application: Application) : AndroidViewModel(application) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var cleared = false
    var onChange: (() -> Unit)? = null
    var entries: List<CatalogEntry> = emptyList(); private set
    var savedRemoteIds: Set<String> = emptySet(); private set
    var savedLoaded = false; private set
    var savedError = false; private set
    var refreshing = true; private set
    var message = "Loading community catalog…"; private set
    var query = ""
    var state: String? = null
    var difficulty: String? = null
    var vehicle: String? = null

    init {
        refreshSaved()
        worker.execute {
            val client = CommunityClient(getApplication())
            val cached = client.cached()
            if (cached != null) post {
                entries = cached
                message = "Saved catalog · refreshing…"
            }
            val result = runCatching { client.refresh() }
            post {
                refreshing = false
                result.onSuccess { entries = it; message = "Catalog up to date." }
                    .onFailure { message = "${if (cached != null) "Showing saved catalog. " else ""}Refresh failed: ${it.message ?: "Check your connection."} Reopen Community to retry." }
            }
        }
    }

    fun refreshSaved() {
        worker.execute {
            runCatching { TrailStore(getApplication()).use { store ->
                store.list().filter { it.remoteId != null && store.loadUsableGpx(it) != null }
                    .mapNotNull { it.remoteId }.toSet()
            } }.onSuccess { ids -> post {
                savedRemoteIds = ids
                savedLoaded = true
                savedError = false
            } }.onFailure { post {
                savedLoaded = true
                savedError = true
            } }
        }
    }

    private fun post(action: () -> Unit) { main.post { if (!cleared) { action(); onChange?.invoke() } } }
    override fun onCleared() { cleared = true; onChange = null; worker.shutdown() }
}
