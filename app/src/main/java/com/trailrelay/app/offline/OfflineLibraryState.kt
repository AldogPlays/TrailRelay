package com.trailrelay.app.offline

/** UI state derived from native region status, with transient errors kept separate. */
enum class OfflineLibraryState { CHECKING, DOWNLOADING, INCOMPLETE, COMPLETE, FAILED }

internal fun offlineLibraryState(
    hasStatus: Boolean,
    complete: Boolean,
    nativeActive: Boolean,
    requestedActive: Boolean,
    error: Boolean,
): OfflineLibraryState = when {
    complete -> OfflineLibraryState.COMPLETE
    error -> OfflineLibraryState.FAILED
    requestedActive || nativeActive -> OfflineLibraryState.DOWNLOADING
    !hasStatus -> OfflineLibraryState.CHECKING
    else -> OfflineLibraryState.INCOMPLETE
}

internal fun canResumeOffline(state: OfflineLibraryState, recognized: Boolean) =
    recognized && state in setOf(OfflineLibraryState.INCOMPLETE, OfflineLibraryState.FAILED)

internal fun isOrphanedOffline(recognized: Boolean, trailId: String?, trailIds: Set<String>) =
    !recognized || trailId !in trailIds

/** Missing native regions are removed from the in-memory list; their trails are untouched. */
internal fun staleRegionIds(knownIds: Set<Long>, nativeIds: Set<Long>) = knownIds - nativeIds
