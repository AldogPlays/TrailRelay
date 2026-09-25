package com.trailrelay.app.offline

import com.trailrelay.app.map.MapMode

/** Called only after the region observer is attached, for both creation and resume.
 * MapLibre 13.6.1's offline loader sends uncached assets to its HTTP loader, which
 * cannot resolve asset://. Seed the unchanged bundled style through MapLibre's
 * cache API before enqueueing activation on the same native database thread.
 */
internal fun activateOfflineRegion(
    complete: Boolean,
    mode: MapMode,
    readAsset: (String) -> ByteArray,
    cacheResource: (String, ByteArray) -> Unit,
    activate: () -> Unit,
    requestStatus: () -> Unit,
) {
    if (complete) return
    cacheResource(mode.styleUri, readAsset(mode.styleUri.removePrefix("asset://")))
    activate()
    requestStatus()
}
