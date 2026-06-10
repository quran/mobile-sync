package com.quran.shared.pipeline.di

import com.quran.shared.auth.service.AuthService
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepository
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepository
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepository
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepository
import com.quran.shared.persistence.repository.note.repository.NotesRepository
import com.quran.shared.persistence.repository.PersistenceResetRepository
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepository
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepository
import com.quran.shared.pipeline.SessionLifecycleCoordinator
import com.quran.shared.pipeline.SyncEnginePipeline
import com.quran.shared.pipeline.SyncAuthService
import com.quran.shared.pipeline.SyncLocalModificationDateStore
import com.quran.shared.pipeline.SyncService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppGraphPublicSurfaceTest {
    @Test
    fun `managed app graph exposes only managed service facades`() {
        val declaredNoArgMethods = AppGraph::class.java.declaredMethods.filter { method ->
            method.parameterCount == 0
        }

        assertFalse(
            declaredNoArgMethods.any { method -> method.returnType == AuthService::class.java },
            "AppGraph must not expose raw auth AuthService"
        )
        assertEquals(SyncAuthService::class.java, AppGraph::class.java.getMethod("getAuthService").returnType)
        assertEquals(SyncService::class.java, AppGraph::class.java.getMethod("getSyncService").returnType)
        assertEquals(
            setOf("getAuthService", "getSyncService"),
            declaredNoArgMethods.map { method -> method.name }.toSet()
        )
    }

    @Test
    fun `managed app graph does not expose write capable persistence repositories`() {
        val publicNoArgMethods = AppGraph::class.java.methods.filter { method ->
            method.parameterCount == 0
        }
        val hiddenRepositoryTypes = setOf(
            BookmarksRepository::class.java,
            CollectionsRepository::class.java,
            CollectionBookmarksRepository::class.java,
            PersistenceImportRepository::class.java,
            NotesRepository::class.java,
            PersistenceResetRepository::class.java,
            ReadingBookmarksRepository::class.java,
            ReadingSessionsRepository::class.java
        )

        assertFalse(
            publicNoArgMethods.any { method -> method.returnType in hiddenRepositoryTypes },
            "AppGraph must not expose write-capable persistence repositories"
        )
    }

    @Test
    fun `managed facades do not expose sync pipeline or write capable repositories`() {
        val hiddenTypes = managedInternalTypes
        val facadeMethods = listOf(SyncService::class.java, SyncAuthService::class.java).flatMap { facade ->
            facade.methods.filter { method -> method.parameterCount == 0 }
        }

        assertFalse(
            facadeMethods.any { method -> method.returnType in hiddenTypes },
            "Managed facades must not expose SyncEnginePipeline or write-capable persistence repositories"
        )
    }

    private companion object {
        val writeCapablePersistenceRepositoryTypes = setOf(
            BookmarksRepository::class.java,
            CollectionsRepository::class.java,
            CollectionBookmarksRepository::class.java,
            PersistenceImportRepository::class.java,
            NotesRepository::class.java,
            PersistenceResetRepository::class.java,
            ReadingBookmarksRepository::class.java,
            ReadingSessionsRepository::class.java
        )

        val managedInternalTypes = writeCapablePersistenceRepositoryTypes + setOf(
            AuthService::class.java,
            SyncEnginePipeline::class.java,
            SyncLocalModificationDateStore::class.java,
            SessionLifecycleCoordinator::class.java
        )
    }
}
