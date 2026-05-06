package com.quran.shared.persistence.repository.importdata

import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.PersistenceImportResult
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines

interface PersistenceImportRepository {
    /**
     * Imports a complete local data set into an empty persistence database.
     *
     * The import is all-or-nothing. If any managed persistence table already has rows,
     * or the payload is invalid, no import rows are persisted.
     */
    @NativeCoroutines
    suspend fun importData(data: PersistenceImportData): PersistenceImportResult
}
