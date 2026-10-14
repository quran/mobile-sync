package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import com.quran.shared.syncv2.SyncRow
import com.quran.shared.syncv2.SyncStore
import com.quran.shared.syncv2.types.syncTypes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An import writes domain rows only. The engine wipes everything when it finds a token without an account, so an
 * import that wrote sync state could make the next sync wipe the user's unsynced data.
 */
class ImportSyncStateTest {
    private lateinit var database: MobileSyncDatabase
    private lateinit var repository: PersistenceImportRepositoryImpl
    private lateinit var store: SyncStore

    @BeforeTest
    fun setup() {
        database = createTestDatabase()
        repository = PersistenceImportRepositoryImpl(database.quran, database.synced)
        store = SyncStore(database.synced)
    }

    @Test
    fun `an import while logged in leaves the sync state as it was`() = runTest {
        val synced = NotesRepositoryImpl(database.synced).addNote("A synced note", 2, 255, 2, 255, at(0))
        store.put("NOTE", SyncRow(synced.id.toLong(), "remote-note", JsonObject(emptyMap())))
        store.token = 42
        store.account = "alice"
        val epoch = store.epoch

        repository.importData(LEGACY, deleteExisting = false, trackHistory = true)
        repository.importData(LEGACY, deleteExisting = true, trackHistory = false)

        assertEquals(42, store.token)
        assertEquals("alice", store.account)
        assertEquals(epoch, store.epoch)
        // The replaced note keeps its base, so the next sync deletes it on the server too.
        assertEquals("remote-note", store.row("NOTE", synced.id.toLong())?.remoteId)
    }

    @Test
    fun `an import while logged out writes no sync state`() = runTest {
        val epoch = store.epoch

        repository.importData(LEGACY, deleteExisting = false, trackHistory = true)
        repository.importData(LEGACY, deleteExisting = true, trackHistory = false)

        assertEquals(0, store.token)
        assertNull(store.account)
        assertEquals(epoch, store.epoch)
        syncTypes(database.synced).forEach { type ->
            assertEquals(emptyList(), database.synced.sync_itemQueries.all(type.name).executeAsList(), type.name)
        }
    }

    private companion object {
        val LEGACY = PersistenceImportData(notes = listOf(ImportNote("A legacy note", 1, 1, 1, 7, at(100), at(100))))
    }
}
