package com.quran.shared.persistence.repository.importdata

import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.PersistenceImportResult
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines

interface PersistenceImportRepository {
    /**
     * Imports a local data set into the persistence database.
     *
     * The import is all-or-nothing. If [deleteExisting] is true, existing rows are
     * deleted first using sync-aware deletion semantics before the payload is merged.
     * If [deleteExisting] is false, the payload is merged into the current data.
     */
    @NativeCoroutines
    suspend fun importData(data: PersistenceImportData): PersistenceImportResult {
        return importData(data = data, deleteExisting = false)
    }

    @NativeCoroutines
    suspend fun importData(
        data: PersistenceImportData,
        deleteExisting: Boolean
    ): PersistenceImportResult
}
