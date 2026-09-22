package com.quran.shared.persistence.repository.importdata

import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.util.SQLITE_MAX_BIND_PARAMETERS

/** Transaction-scoped history lookup and recording for one import call. */
internal class ImportHistoryTracker(
    private val database: QuranDatabase,
    fingerprints: List<String>
) {
    private val recordedFingerprints = fingerprints.distinct()
        .chunked(SQLITE_MAX_BIND_PARAMETERS)
        .flatMap { fingerprints ->
            database.import_trackingQueries
                .getRecordedImportHistory(fingerprints)
                .executeAsList()
        }
        .toMutableSet()

    fun <T> unseen(
        candidates: List<T>,
        fingerprint: (T) -> String
    ): List<T> = candidates.filter { candidate -> fingerprint(candidate) !in recordedFingerprints }

    fun record(fingerprint: String) {
        if (!recordedFingerprints.add(fingerprint)) return
        database.import_trackingQueries.recordImportHistory(fingerprint)
    }
}
