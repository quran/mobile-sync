@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.persistence.repository

import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.RemoteReadingSession
import com.quran.shared.persistence.repository.recentpage.repository.RecentPagesRepositoryImpl
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class RecentPagesRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var repository: RecentPagesRepositoryImpl

    @BeforeTest
    fun setup() {
        database = QuranDatabase(TestDatabaseDriver().createDriver())
        repository = RecentPagesRepositoryImpl(database)
    }

    @Test
    fun `applyRemoteChanges removes deleted remote recent page after successful sync`() = runTest {
        database.recent_pagesQueries.persistRemoteRecentPage(
            remote_id = "remote-recent-page-id",
            page = 42L,
            first_ayah_sura = 2L,
            first_ayah_verse = 255L,
            created_at = 1L,
            modified_at = 1L
        )

        repository.deleteRecentPage(42)

        val localMutations = repository.fetchMutatedRecentPages()
        assertEquals(1, localMutations.size)
        assertEquals(Mutation.DELETED, localMutations.single().mutation)

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        page = 42,
                        chapterNumber = 2,
                        verseNumber = 255,
                        lastUpdated = Instant.fromEpochMilliseconds(1000L).toPlatform()
                    ),
                    remoteID = "remote-recent-page-id",
                    mutation = Mutation.DELETED
                )
            ),
            localMutationsToClear = localMutations
        )

        assertTrue(repository.getRecentPages().isEmpty())
        assertNull(database.recent_pagesQueries.getRecentPageForPage(42L).executeAsOneOrNull())
    }
}
