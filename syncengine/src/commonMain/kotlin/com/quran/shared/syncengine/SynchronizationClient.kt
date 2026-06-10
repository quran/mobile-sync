@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LocalMutationAck
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncBookmark
import com.quran.shared.syncengine.model.SyncCollectionBookmark
import com.quran.shared.syncengine.model.SyncCollection
import com.quran.shared.syncengine.model.SyncNote
import com.quran.shared.syncengine.model.SyncReadingSession
import com.quran.shared.syncengine.network.HttpClientFactory
import io.ktor.client.HttpClient
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

interface LocalDataFetcher<Model> {
    /**
     * Fetches local mutations that have occurred since the given timestamp (epoch milliseconds).
     */
    suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<Model>>

    /**
     * Checks if the given remote IDs exist locally.
     * @param remoteIDs List of remote IDs to check
     * @return Map of remote ID to boolean indicating if it exists locally
     */
    suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean>

    /**
     * Fetches a local model by its remote ID.
     * @param remoteId Remote ID of the model to fetch
     * @return Model if found locally, null otherwise
     */
    suspend fun fetchLocalModel(remoteId: String): Model?

    suspend fun markLocalMutationsInFlight(localMutations: List<LocalModelMutation<Model>>): List<LocalMutationAck> =
        emptyList()

    suspend fun rollbackLocalMutationsInFlight(acks: List<LocalMutationAck>) = Unit
}

interface ResultNotifier<Model> {
    /**
     * Applies completed resource changes. Sync token publication is intentionally handled by
     * [SyncCompletionFinalizer] after every resource plan has completed successfully.
     */
    suspend fun didSucceed(
        newToken: Long,
        newRemoteMutations: List<RemoteModelMutation<Model>>,
        processedLocalMutations: List<LocalModelMutation<Model>>
    )

    suspend fun didFail(message: String)
}

/**
 * Publishes the shared sync token after every resource plan has applied its local work successfully.
 */
fun interface SyncCompletionFinalizer {
    suspend fun completeSync(newToken: Long)
}

interface LocalModificationDateFetcher {
    /**
     * Returns the last local modification timestamp in epoch milliseconds.
     */
    suspend fun localLastModificationDate(): Long?
}

// This will be duplicated per each model type (or generalized), as defined by the BE.
class BookmarksSynchronizationConfigurations(
    // Probably, add configurations to select bookmark types to process.
    val localDataFetcher: LocalDataFetcher<SyncBookmark>,
    val resultNotifier: ResultNotifier<SyncBookmark>,
    val localModificationDateFetcher: LocalModificationDateFetcher
)

class CollectionsSynchronizationConfigurations(
    val localDataFetcher: LocalDataFetcher<SyncCollection>,
    val resultNotifier: ResultNotifier<SyncCollection>,
    val localModificationDateFetcher: LocalModificationDateFetcher
)

class CollectionBookmarksSynchronizationConfigurations(
    val localDataFetcher: LocalDataFetcher<SyncCollectionBookmark>,
    val resultNotifier: ResultNotifier<SyncCollectionBookmark>,
    val localModificationDateFetcher: LocalModificationDateFetcher
)

class NotesSynchronizationConfigurations(
    val localDataFetcher: LocalDataFetcher<SyncNote>,
    val resultNotifier: ResultNotifier<SyncNote>,
    val localModificationDateFetcher: LocalModificationDateFetcher
)

class ReadingSessionsSynchronizationConfigurations(
    val localDataFetcher: LocalDataFetcher<SyncReadingSession>,
    val resultNotifier: ResultNotifier<SyncReadingSession>,
    val localModificationDateFetcher: LocalModificationDateFetcher
)

interface AuthenticationDataFetcher {
    suspend fun fetchAuthenticationHeaders(): Map<String, String>
    fun isLoggedIn(): Boolean
}

interface SyncLifecycleGate {
    fun canStartSync(): Boolean = true
    suspend fun captureSyncEpoch(): Long = 0L
    suspend fun checkSyncEpoch(epoch: Long) = Unit
    suspend fun admitSyncPost(epoch: Long) {
        checkSyncEpoch(epoch)
    }
    suspend fun <T> withValidSyncEpoch(epoch: Long, block: suspend () -> T): T {
        checkSyncEpoch(epoch)
        return block()
    }
}

class SyncWriteBoundaryContext(
    private val checkBoundary: suspend () -> Unit
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SyncWriteBoundaryContext>

    suspend fun checkBoundary() {
        checkBoundary.invoke()
    }
}

suspend fun checkCurrentSyncWriteBoundary() {
    currentCoroutineContext()[SyncWriteBoundaryContext]?.checkBoundary()
}

class SyncOperationInvalidatedException(message: String) : Exception(message)

interface SynchronizationClient {
    fun localDataUpdated()
    fun applicationStarted()
    fun triggerSyncImmediately()
    fun cancelSyncing()
    suspend fun cancelSyncingAndJoin()
}

data class SynchronizationEnvironment(val endPointURL: String)

object SynchronizationClientBuilder {
    fun build(
        environment: SynchronizationEnvironment,
        authFetcher: AuthenticationDataFetcher,
        bookmarksConfigurations: BookmarksSynchronizationConfigurations,
        collectionsConfigurations: CollectionsSynchronizationConfigurations? = null,
        collectionBookmarksConfigurations: CollectionBookmarksSynchronizationConfigurations? = null,
        notesConfigurations: NotesSynchronizationConfigurations? = null,
        readingSessionsConfigurations: ReadingSessionsSynchronizationConfigurations? = null,
        syncCompletionFinalizer: SyncCompletionFinalizer = SyncCompletionFinalizer { },
        syncLifecycleGate: SyncLifecycleGate = object : SyncLifecycleGate {},
        httpClient: HttpClient? = null
    ): SynchronizationClient {
        val adapters = buildList {
            add(BookmarksSyncAdapter(bookmarksConfigurations))
            collectionsConfigurations?.let { add(CollectionsSyncAdapter(it)) }
            collectionBookmarksConfigurations?.let { add(CollectionBookmarksSyncAdapter(it)) }
            notesConfigurations?.let { add(NotesSyncAdapter(it)) }
            readingSessionsConfigurations?.let { add(ReadingSessionsSyncAdapter(it)) }
        }
        return SynchronizationClientImpl(
            environment,
            httpClient ?: HttpClientFactory.createHttpClient(),
            adapters,
            authFetcher,
            syncCompletionFinalizer,
            syncLifecycleGate
        )
    }
}
