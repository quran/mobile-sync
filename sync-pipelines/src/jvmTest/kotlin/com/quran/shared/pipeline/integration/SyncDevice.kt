package com.quran.shared.pipeline.integration

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.AuthRuntimeConfig
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.persistence.DriverFactory
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.createQuranDatabase
import com.quran.shared.pipeline.LogoutResult
import com.quran.shared.pipeline.QuranDataService
import com.quran.shared.pipeline.QuranDataServiceSynchronizationClientFactory
import com.quran.shared.pipeline.SyncAuthService
import com.quran.shared.pipeline.SyncStatus
import com.quran.shared.pipeline.di.AppGraph
import com.quran.shared.pipeline.integration.server.FakeSyncServer
import com.quran.shared.pipeline.integration.server.RequestRecord
import com.quran.shared.pipeline.storage.MobileSyncStorage
import com.quran.shared.syncengine.SynchronizationClientImpl
import com.quran.shared.syncengine.SynchronizationEnvironment
import com.quran.shared.syncengine.scheduling.FailureRetryingConfig
import com.quran.shared.syncengine.scheduling.SchedulerTimings
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toFlowSettings
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createDynamicGraphFactory
import kotlinx.coroutines.flow.map
import org.publicvalue.multiplatform.oidc.tokenstore.SettingsStore
import org.publicvalue.multiplatform.oidc.tokenstore.SettingsTokenStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * Scheduler timings for a simulated device. By default nothing syncs on its own: app start, local
 * edits and the periodic sync wait an hour, so a device is "offline" until the test syncs it.
 * Retries keep production's count and multiplier with a 5 ms base, so a run that fails for good
 * gives up in well under a second.
 */
data class DeviceTimings(
    val localChangeDelay: Duration = 1.hours,
    val appStartDelay: Duration = 1.hours,
    val periodicInterval: Duration = 1.hours,
    val retryBaseDelay: Duration = 5.milliseconds,
    val retryMultiplier: Double = 2.5,
    val maximumRetries: Int = 5
) {
    internal fun toSchedulerTimings() = SchedulerTimings(
        standardInterval = periodicInterval,
        appRefreshInterval = appStartDelay,
        localDataModifiedInterval = localChangeDelay,
        failureRetryingConfig = FailureRetryingConfig(retryBaseDelay, retryMultiplier, maximumRetries)
    )
}

/**
 * One sync run as the device saw it: its final status and the requests it made. A [skipped] run
 * found no session (for example after an expired one) and kept the previous status.
 */
data class SyncRun(val status: SyncStatus, val requests: List<RequestRecord>, val skipped: Boolean = false) {
    val gets: Int get() = requests.count { it.method == "GET" }
    val posts: Int get() = requests.count { it.method == "POST" }
    val postStatuses: List<Int> get() = requests.filter { it.method == "POST" }.map { it.status }
    val succeeded: Boolean get() = status is SyncStatus.Succeeded
}

/**
 * A simulated app install: the production [AppGraph] with its own in-memory SQLDelight database,
 * the real sync engine and Ktor client pointed at [server], and [FakeAuthRepository] at the OIDC
 * boundary. Tests use only [data] and [authService], the app-facing API.
 */
class SyncDevice internal constructor(
    val name: String,
    val server: FakeSyncServer,
    private val timings: DeviceTimings
) {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { QuranDatabase.Schema.create(it) }
    private val database = createQuranDatabase(driver)
    private val storage = MobileSyncStorage(SettingsTokenStore(InMemorySettingsStore()), MapSettings().toFlowSettings())

    /** The OIDC boundary: choose the account, expire the session, rotate tokens, fail remote logout. */
    val auth = FakeAuthRepository(name, server)

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
    suspend fun login(account: String): SyncStatus {
        auth.signInAs(account)
        return recordRun { authService.login() }
    }

    /** Logs out through the public API, which clears local data. */
    suspend fun logout(): LogoutResult = authService.logout()

    /**
     * Runs one sync now, with its retries, and returns how it ended. Waits first for a run already
     * in flight, so the returned run started after this call. A run that finds no session (an
     * expired one) returns the unchanged status, with [SyncRun.skipped] set on [lastRun].
     */
    suspend fun syncNow(): SyncStatus {
        check(auth.token != null) { "$name is signed out; sign in before syncNow()" }
        status.awaitNotSyncing()
        return recordRun { data.triggerSync(immediately = true) }
    }

    /** Waits for the next run that starts after this call, started by something other than the test. */
    suspend fun awaitSync(): SyncStatus = recordRun { }

    /**
     * Relaunches the app: a new graph over the same database and auth session. When signed in,
     * waits for the sync the stored session starts.
     */
    suspend fun restart(): SyncStatus? {
        data.clearAndJoin()
        status.close()
        val requestsBefore = server.requests(name).size
        graph = newGraph()
        status = SyncStatusRecorder(data.syncStatus)
        // Mark from the recorder's start: the stored session's sync may begin before we get here.
        return if (auth.token != null) recordRun(mark = 0, requestsBefore) { } else {
            data.awaitInitialization()
            null
        }
    }

    /** What the public flows show now, normalized for comparison with other devices and the server. */
    suspend fun snapshot(): UserDataSnapshot = UserDataSnapshot.of(data)

    private suspend fun recordRun(
        mark: Int = status.mark(),
        requestsBefore: Int = server.requests(name).size,
        start: suspend () -> Unit
    ): SyncStatus {
        val denialsBefore = auth.headerDenials.value
        start()
        val ended = status.awaitRunAfter(mark, name, skipped = auth.headerDenials.map { it > denialsBefore })
        val result = ended ?: data.syncStatus.value
        lastRun = SyncRun(result, server.requests(name).drop(requestsBefore), skipped = ended == null)
        return result
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
    }
}

/**
 * The per-device graph bindings: this device's database instead of the process-wide one, the fake
 * OIDC boundary, and short scheduler timings through the engine's internal timing seam.
 */
@BindingContainer
internal class DeviceBindings(
    private val database: QuranDatabase,
    private val authRepository: AuthRepository,
    private val timings: SchedulerTimings
) {
    @Provides
    fun database(): QuranDatabase = database

    @Provides
    fun authRepository(): AuthRepository = authRepository

    @Provides
    fun synchronizationClientFactory(): QuranDataServiceSynchronizationClientFactory =
        QuranDataServiceSynchronizationClientFactory { pipeline, environment, dates, auth, gate, callback, observer ->
            val client = pipeline.setup(environment, dates, auth, gate, callback, observer)
            (client as SynchronizationClientImpl).withSchedulerTimings(timings)
        }
}

private class InMemorySettingsStore : SettingsStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun get(key: String): String? = values[key]
    override suspend fun put(key: String, value: String) { values[key] = value }
    override suspend fun remove(key: String) { values.remove(key) }
    override suspend fun clear() = values.clear()
}
