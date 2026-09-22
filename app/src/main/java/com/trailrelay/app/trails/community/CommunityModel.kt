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
    var refreshing = true; private set
    var downloading = false; private set
    var message = "Loading community catalog…"; private set
    var downloadedId: String? = null; private set
    var query = ""
    var state: String? = null
    var difficulty: String? = null
    var vehicle: String? = null
    var selected: CatalogEntry? = null

    init {
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

    fun download(entry: CatalogEntry) {
        if (downloading) return
        downloading = true
        message = "Downloading ${entry.name}…"
        onChange?.invoke()
        worker.execute {
            val result = runCatching { TrailStore(getApplication()).use { it.download(entry) } }
            post {
                downloading = false
                result.onSuccess { downloadedId = it.id; message = "${it.name} saved in My Trails." }
                    .onFailure { message = "Download failed: ${it.message ?: "Check your connection and storage space."}" }
            }
        }
    }

    private fun post(action: () -> Unit) { main.post { if (!cleared) { action(); onChange?.invoke() } } }
    override fun onCleared() { cleared = true; onChange = null; worker.shutdown() }
}
