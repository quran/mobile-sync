package com.quran.shared.pipeline.integration

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.AuthRuntimeConfig
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.persistence.DriverFactory
import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.MobileSyncSchema
import com.quran.shared.pipeline.LogoutResult
import com.quran.shared.pipeline.QuranDataService
import com.quran.shared.pipeline.QuranDataServiceSynchronizationClientFactory
import com.quran.shared.pipeline.SyncAuthService
import com.quran.shared.pipeline.SyncStatus
import com.quran.shared.pipeline.SynchronizationEnvironment
import com.quran.shared.pipeline.di.AppGraph
import com.quran.shared.pipeline.integration.server.FakeSyncServer
import com.quran.shared.pipeline.integration.server.RequestGate
import com.quran.shared.pipeline.integration.server.RequestRecord
import com.quran.shared.pipeline.sync.FailureRetryingConfig
import com.quran.shared.pipeline.sync.SchedulerTimings
import com.quran.shared.pipeline.sync.SyncClient
import com.quran.shared.pipeline.storage.MobileSyncStorage
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toFlowSettings
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createDynamicGraphFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.publicvalue.multiplatform.oidc.tokenstore.SettingsStore
import org.publicvalue.multiplatform.oidc.tokenstore.SettingsTokenStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import java.io.File
import java.util.Properties

/**
 * Scheduler timings for a simulated device. By default nothing syncs on its own: app start, local
 * edits, a return to the foreground and the periodic sync wait an hour, so a device is "offline" until the test syncs it.
 * Retries keep production's count and multiplier with a 5 ms base, so a run that fails for good
 * gives up in well under a second.
 */
data class DeviceTimings(
    val localChangeDelay: Duration = 1.hours,
    val appStartDelay: Duration = 1.hours,
    val foregroundDelay: Duration = 1.hours,
    val periodicInterval: Duration = 1.hours,
    val retryBaseDelay: Duration = 5.milliseconds,
    val retryMultiplier: Double = 2.5,
    val maximumRetries: Int = 5
) {
    internal fun toSchedulerTimings() = SchedulerTimings(
        standardInterval = periodicInterval,
        appRefreshInterval = appStartDelay,
        localDataModifiedInterval = localChangeDelay,
        foregroundInterval = foregroundDelay,
        failureRetryingConfig = FailureRetryingConfig(retryBaseDelay, retryMultiplier, maximumRetries)
    )
}

/**
 * One sync run as the device saw it: how it ended and the requests it made. [status] is null for a
 * [skipped] run, which found no session (for example an expired one) and synced nothing.
 */
data class SyncRun(val status: SyncStatus?, val requests: List<RequestRecord>) {
    val skipped: Boolean get() = status == null

    val gets: Int get() = requests.count { it.method == "GET" }
    val posts: Int get() = requests.count { it.method == "POST" }
    val postStatuses: List<Int> get() = requests.filter { it.method == "POST" }.map { it.status }

    /** The run synced: it was not skipped and ended [SyncStatus.Succeeded]. */
    val succeeded: Boolean get() = status is SyncStatus.Succeeded
}

/** [assertSucceeded]'s failure, carrying the run so [knownIssue] can pin it (see [syncRejectedWith]). */
class SyncRunFailure(val run: SyncRun, message: String) : AssertionError(message)

/** Fails unless [run] synced: a skipped run, or one that ended in any other status, fails. */
fun assertSucceeded(run: SyncRun, message: String? = null) {
    if (run.succeeded) return
    val what = if (run.skipped) "a skipped run (no session)" else "${run.status}"
    throw SyncRunFailure(run, (message?.let { "$it: " } ?: "") + "expected a successful sync, got $what; POST statuses ${run.postStatuses}")
}

/**
 * A simulated app install: the production [AppGraph] with its own in-memory SQLDelight database,
 * the real sync engine and Ktor client pointed at [server], and [FakeAuthRepository] at the OIDC
 * boundary. Tests use only [data] and [authService], the app-facing API.
 *
 * Harness assumption: a run that finds no session is seen as skipped only when it asks
 * [FakeAuthRepository] for headers, which happens when a session expires. A run skipped earlier,
 * because the sync lifecycle gate is closed by a logout or reset in progress, reports nothing; the
 * harness then fails fast with [HarnessTimeoutException] after
 * [SyncStatusRecorder.START_TIMEOUT] instead of waiting for the run timeout.
 */
class SyncDevice internal constructor(
    val name: String,
    val server: FakeSyncServer,
    private val timings: DeviceTimings
) {
    // A file, not SQLDelight's in-memory database, whose one connection and transaction all threads share: a write
    // could join another thread's transaction and lose its notification. A file binds transactions to their thread.
    private val file = File.createTempFile("sync-device-$name-", ".db").apply { deleteOnExit() }
    private val driver = JdbcSqliteDriver(
        "jdbc:sqlite:${file.absolutePath}",
        Properties().apply {
            put("foreign_keys", "true")
            put("journal_mode", "WAL")
            put("busy_timeout", "10000")
        }
    ).also { MobileSyncSchema.create(it) }
    private val database = MobileSyncDatabase(driver)
    private val storage = MobileSyncStorage(SettingsTokenStore(InMemorySettingsStore()), MapSettings().toFlowSettings())

    /** The OIDC boundary: choose the account, expire the session, rotate tokens, fail remote logout. */
    val auth = FakeAuthRepository(name, server)

    // A new install has no session, so nothing runs on its own before the recorder subscribes.
    private var graph: AppGraph = newGraph()
    private var status = SyncStatusRecorder(graph.quranDataService.syncStatus)

    /** The app-facing data API. */
    val data: QuranDataService get() = graph.quranDataService

    /** The app-facing auth API. */
    val authService: SyncAuthService get() = graph.authService

    /** The latest run [syncNow], [login], [awaitSync] or [restart] waited for. */
    var lastRun: SyncRun? = null
        private set

    /** Signs in to [account] and waits for the sync the login starts. */
    suspend fun login(account: String): SyncRun {
        auth.signInAs(account)
        return recordRun { authService.login() }
    }

    /** Logs out through the public API, which clears local data. */
    suspend fun logout(): LogoutResult = authService.logout()

    /**
     * Runs one sync now, with its retries, and returns it. Waits first for an attempt already
     * running, so the returned run started after this call. A run waiting to retry doesn't hold it
     * up: the sync now usually replaces its retry (that run then ends cancelled), but the retry's
     * timer may fire between the two, and then the returned run is the earlier one, as its retry
     * ends it. A run that finds no session is returned with no status ([SyncRun.skipped]);
     * [assertSucceeded] fails on it.
     *
     * A run publishes its end before the scheduler has finished it, and a scheduler may drop an
     * immediate trigger that arrives in between. So the trigger is repeated until a run starts
     * ([SyncStatusRecorder.awaitRunAfter]).
     */
    suspend fun syncNow(): SyncRun {
        checkNotNull(auth.accessToken()) { "$name is signed out; sign in before syncNow()" }
        status.awaitNotSyncing(name)
        val trigger: suspend () -> Unit = { data.triggerSync(immediately = true) }
        return recordRun(retrigger = trigger, start = trigger)
    }

    /**
     * Syncs while [gate] holds this device's POST at the server: starts [syncNow], waits until the
     * POST reaches the gate, runs [whileHeld], then releases the gate and returns the run. The gate
     * is released even when [whileHeld] fails.
     */
    suspend fun syncWhileHeld(gate: RequestGate, whileHeld: suspend () -> Unit): SyncRun = coroutineScope {
        val sync = async { syncNow() }
        withContext(Dispatchers.IO) { gate.awaitArrived() }
        try {
            whileHeld()
        } finally {
            gate.release()
        }
        sync.await()
    }

    /** Waits for the next run that starts after this call, started by something other than the test. */
    suspend fun awaitSync(): SyncRun = recordRun { }

    /**
     * Relaunches the app: a new graph over the same database and token store. Waits for launch
     * recovery, then, when still signed in, for the sync the stored session starts.
     */
    suspend fun restart(): SyncRun? {
        data.clearAndJoin()
        status.close()
        val requestsBefore = server.requests(name).size
        val denialsBefore = auth.headerDenials.value
        // QuranDataService and AuthService start their work on Main. Building the graph and
        // subscribing on Main means the stored session's sync cannot start, let alone finish,
        // before the recorder sees it.
        withContext(Dispatchers.Main) {
            graph = newGraph()
            status = SyncStatusRecorder(graph.quranDataService.syncStatus)
        }
        // A logout that the previous launch left unfinished completes during launch recovery and
        // signs the device out, so the session is read only after it.
        data.awaitInitialization()
        if (auth.accessToken() == null) return null
        return recordRun(mark = 0, requestsBefore, denialsBefore) { }
    }

    /** What the public flows show now, normalized for comparison with other devices and the server. */
    suspend fun snapshot(): UserDataSnapshot = UserDataSnapshot.of(data)

    private suspend fun recordRun(
        mark: Int = status.mark(),
        requestsBefore: Int = server.requests(name).size,
        denialsBefore: Int = auth.headerDenials.value,
        retrigger: (suspend () -> Unit)? = null,
        start: suspend () -> Unit
    ): SyncRun {
        start()
        val ended = status.awaitRunAfter(mark, name, skipped = auth.headerDenials.map { it > denialsBefore }, retrigger)
        // FakeAuthRepository counts the denial before the app sees the empty headers and ends the
        // session, so a skipped run is over only once the app shows that.
        if (ended == null) status.awaitSignedOut(name, authService.authState)
        return SyncRun(ended, server.requests(name).drop(requestsBefore)).also { lastRun = it }
    }

    private fun newGraph(): AppGraph =
        createDynamicGraphFactory<AppGraph.Factory>(DeviceBindings(database, auth, timings.toSchedulerTimings()))
            .create(
                driverFactory = DriverFactory(),
                storage = storage,
                environment = SynchronizationEnvironment(server.url),
                authRuntimeConfig = AuthRuntimeConfig.Configured(AuthConfig(clientId = FakeAuthRepository.CLIENT_ID))
            )

    internal suspend fun shutdown() {
        data.clearAndJoin()
        status.close()
        driver.close()
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }
}

/**
 * The per-device graph bindings, over the app's own graph inputs: this device's database, the
 * fake OIDC boundary keeping tokens in the graph's token store, and short scheduler timings.
 */
@BindingContainer
internal class DeviceBindings(
    private val database: MobileSyncDatabase,
    private val authRepository: FakeAuthRepository,
    private val timings: SchedulerTimings
) {
    /**
     * Replaces the app's database binding with this device's in-memory one. It cannot go through
     * [DriverFactory] yet: the JVM `DriverFactory` is a final class that always opens `user_data.db` in
     * the working directory, and the production binding caches one database for the whole process.
     * So Metro reports the `DriverFactory` graph input as unused for device graphs.
     */
    @Provides
    fun database(): MobileSyncDatabase = database

    @Provides
    fun authRepository(storage: MobileSyncStorage): AuthRepository = authRepository.useTokenStore(storage.tokenStore)

    @Provides
    fun synchronizationClientFactory(): QuranDataServiceSynchronizationClientFactory =
        QuranDataServiceSynchronizationClientFactory { environment, account, gate, observer ->
            SyncClient.create(database.synced, environment, account, gate, observer, timings)
        }
}

private class InMemorySettingsStore : SettingsStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun get(key: String): String? = values[key]
    override suspend fun put(key: String, value: String) { values[key] = value }
    override suspend fun remove(key: String) { values.remove(key) }
    override suspend fun clear() = values.clear()
}
