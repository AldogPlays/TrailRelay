package com.trailrelay.app.trails

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import java.util.concurrent.Executors

/** Keeps an in-progress import and its result across library Activity recreation. */
class TrailLibraryModel(application: Application) : AndroidViewModel(application) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    var trails: List<Trail> = emptyList()
        private set
    var busy = false
        private set
    var message: String? = null
        private set
    var onChange: (() -> Unit)? = null
    private var cleared = false
    private var pendingImport: Uri? = null

    init { refresh() }

    fun refresh() = run(null)
    fun import(uri: Uri) {
        if (busy) pendingImport = uri else run(uri)
    }

    private fun run(uri: Uri?) {
        if (busy) return
        busy = true
        message = null
        onChange?.invoke()
        worker.execute {
            val result = runCatching {
                TrailStore(getApplication()).use { store ->
                    val imported = uri?.let(store::import)
                    store.list() to imported
                }
            }
            main.post {
                if (!cleared) {
                    busy = false
                    result.onSuccess { (items, imported) ->
                        trails = items
                        message = imported?.let { "${it.name} is saved in My Trails." }
                    }.onFailure {
                        message = if (it is GpxException) it.message else
                            "Unable to ${if (uri == null) "load trails" else "import GPX"}. Check that the file is accessible and device storage has space."
                    }
                    onChange?.invoke()
                    pendingImport?.let { uri ->
                        pendingImport = null
                        run(uri)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        cleared = true
        onChange = null
        worker.shutdown()
    }
}
