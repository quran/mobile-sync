package com.quran.shared.persistence.repository.recentpage.repository

import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.RemoteReadingSession
import com.quran.shared.persistence.model.RecentPage
import com.quran.shared.persistence.repository.recentpage.extension.toRecentPage
import com.quran.shared.persistence.repository.recentpage.extension.toRecentPageMutation
import com.quran.shared.persistence.util.SQLITE_MAX_BIND_PARAMETERS
import com.quran.shared.persistence.util.fromPlatform
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList

@Inject
@SingleIn(AppScope::class)
class RecentPagesRepositoryImpl(
    private val database: QuranDatabase
) : RecentPagesRepository, RecentPagesSynchronizationRepository {

    private val logger = Logger.withTag("RecentPagesRepository")
    private val recentPagesQueries = lazy { database.recent_pagesQueries }

    override suspend fun getRecentPages(): List<RecentPage> {
        return withContext(Dispatchers.IO) {
            recentPagesQueries.value.getRecentPages()
                .executeAsList()
                .map { it.toRecentPage() }
        }
    }

    override fun getRecentPagesFlow(): Flow<List<RecentPage>> {
        return recentPagesQueries.value.getRecentPages()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list -> list.map { it.toRecentPage() } }
    }

    override suspend fun addRecentPage(page: Int, firstAyahSura: Int, firstAyahVerse: Int): RecentPage {
        logger.i { "Adding recent page $page ($firstAyahSura:$firstAyahVerse)" }
        return withContext(Dispatchers.IO) {
            recentPagesQueries.value.addRecentPage(
                page = page.toLong(),
                first_ayah_sura = firstAyahSura.toLong(),
                first_ayah_verse = firstAyahVerse.toLong()
            )
            val record = recentPagesQueries.value.getRecentPageForPage(page.toLong())
                .executeAsOneOrNull()
            requireNotNull(record) { "Expected recent page for page $page after insert." }
            record.toRecentPage()
        }
    }

    override suspend fun deleteRecentPage(page: Int): Boolean {
        logger.i { "Deleting recent page for page $page" }
        withContext(Dispatchers.IO) {
            recentPagesQueries.value.deleteRecentPage(page.toLong())
        }
        return true
    }

    override suspend fun fetchMutatedRecentPages(): List<LocalModelMutation<RecentPage>> {
        return withContext(Dispatchers.IO) {
            recentPagesQueries.value.getUnsyncedRecentPages()
                .executeAsList()
                .map { it.toRecentPageMutation() }
        }
    }

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteReadingSession>>,
        localMutationIdsToClear: List<String>
    ) {
        logger.i {
            "Applying remote changes for recent pages: updates=${updatesToPersist.size}, " +
                "toClear=${localMutationIdsToClear.size}"
        }
        return withContext(Dispatchers.IO) {
            database.transaction {
                // Apply remote updates
                updatesToPersist.forEach { remote ->
                    when (remote.mutation) {
                        Mutation.CREATED, Mutation.MODIFIED -> {
                            val model = remote.model
                            val existingPage = recentPagesQueries.value.getRecentPageByRemoteId(remote.remoteID)
                                .executeAsOneOrNull()
                                ?: recentPagesQueries.value.getRecentPages()
                                    .executeAsList()
                                    .firstOrNull { record ->
                                        record.first_ayah_sura?.toInt() == model.chapterNumber &&
                                            record.first_ayah_verse?.toInt() == model.verseNumber
                                    }
                            if (existingPage == null) {
                                logger.w {
                                    "Skipping reading session mutation without local page match: " +
                                        "remoteId=${remote.remoteID}, chapter=${model.chapterNumber}, verse=${model.verseNumber}"
                                }
                                return@forEach
                            }
                            val updatedAt = model.lastUpdated.fromPlatform().toEpochMilliseconds()
                            recentPagesQueries.value.persistRemoteRecentPage(
                                remote_id = remote.remoteID,
                                page = existingPage.page,
                                first_ayah_sura = model.chapterNumber.toLong(),
                                first_ayah_verse = model.verseNumber.toLong(),
                                created_at = updatedAt,
                                modified_at = updatedAt
                            )
                        }
                        Mutation.DELETED -> {
                            recentPagesQueries.value.hardDeleteRecentPageFor(remoteID = remote.remoteID)
                        }
                    }
                }

                // Clear local mutations after remote upserts so newly-synced rows keep their remote IDs.
                localMutationIdsToClear.forEach { localId ->
                    recentPagesQueries.value.clearLocalMutationFor(id = localId.toLong())
                }
            }
        }
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> {
        if (remoteIDs.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            val existentIDs = mutableSetOf<String>()
            remoteIDs.chunked(SQLITE_MAX_BIND_PARAMETERS).forEach { chunk ->
                existentIDs.addAll(
                    recentPagesQueries.value.checkRemoteIDsExistence(chunk)
                        .executeAsList()
                        .mapNotNull { it.remote_id }
                )
            }
            remoteIDs.associateWith { existentIDs.contains(it) }
        }
    }

    override suspend fun fetchRecentPageByRemoteId(remoteId: String): RecentPage? {
        return withContext(Dispatchers.IO) {
            recentPagesQueries.value.getRecentPageByRemoteId(remoteId)
                .executeAsOneOrNull()
                ?.toRecentPage()
        }
    }
}
