@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.quran.shared.pipeline.integration

import com.quran.shared.pipeline.SyncStatus
import com.quran.shared.pipeline.integration.server.FakeSyncServer
import com.quran.shared.pipeline.integration.server.WebActor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Runs one end-to-end sync scenario and cleans up after it.
 *
 * # Writing a scenario
 *
 * Each [SyncDevice] is a full app install: the production graph with its own in-memory database
 * and the real sync engine and Ktor client, talking over loopback HTTP to one shared
 * [FakeSyncServer]. Tests drive devices only through the public API ([SyncDevice.data] and
 * [SyncDevice.authService]) and assert on public flows, return values, and the fake server's state
 * and request log. They never read engine tables or the sync token, so the same scenario can run
 * against a rewritten engine.
 *
 * ```
 * @Test
 * fun `a note reaches the other device`() = syncScenario {
 *     val phone = device("phone")
 *     val tablet = device("tablet")
 *     phone.login(ALICE)
 *     tablet.login(ALICE)
 *
 *     phone.data.addNote("Patience is light", 2, 153, 2, 153)
 *     assertSucceeded(phone.syncNow())
 *     assertSucceeded(tablet.syncNow())
 *
 *     assertEquals(listOf("Patience is light"), tablet.data.notes.first().map { it.body })
 *     assertConverged(ALICE, phone, tablet)
 * }
 * ```
 *
 * - **Syncing.** Nothing syncs on its own (see [DeviceTimings]): a device that is not told to sync
 *   is offline. [SyncDevice.syncNow] calls `triggerSync(immediately = true)` and waits on
 *   `syncStatus` for the run's end, retries included; [SyncDevice.lastRun] has its requests.
 *   [syncAll] syncs devices in order; [syncUntilQuiescent] repeats until a round makes no POST.
 * - **Accounts.** [SyncDevice.login] signs in through [FakeAuthRepository] and waits for the sync
 *   the login starts. [SyncDevice.auth] can expire the session or rotate the token.
 * - **The web.** [web] writes straight into the server with REST semantics, also between a
 *   device's GET and POST through [FakeSyncServer.faults] (`beforePost`, `betweenPages`).
 * - **Faults.** `server.faults` fails, drops, blocks or loses requests, rejects chosen mutations,
 *   and turns on BE1's `mutationIndex`.
 * - **Known issues.** Wrap a test that fails today in [knownIssue] with its `SYNC-ISSUES.md` id.
 * - **Time.** Pass explicit `PlatformDateTime` timestamps for anything compared by time, and move
 *   the server's clock with `server.clock`.
 *
 * After the block, every account the server knows is checked for duplicates
 * ([com.quran.shared.pipeline.integration.server.ServerState.assertNoDuplicates]) unless
 * [checkDuplicates] is false.
 */
fun syncScenario(checkDuplicates: Boolean = true, block: suspend SyncScenario.() -> Unit) = runBlocking {
    val scenario = SyncScenario()
    try {
        scenario.block()
        if (checkDuplicates) scenario.assertNoServerDuplicates()
    } finally {
        scenario.close()
    }
}

class SyncScenario internal constructor() {
    private val main = Executors.newSingleThreadExecutor { Thread(it, "device-main").apply { isDaemon = true } }
    private val devices = mutableListOf<SyncDevice>()

    val server = FakeSyncServer()

    init {
        // QuranDataService and AuthService run on Dispatchers.Main, like the app's main thread.
        Dispatchers.setMain(main.asCoroutineDispatcher())
    }

    /** A new app install. Device names appear in the server's request log. */
    fun device(name: String, timings: DeviceTimings = DeviceTimings()): SyncDevice {
        require(devices.none { it.name == name }) { "Device $name already exists" }
        return SyncDevice(name, server, timings).also { devices += it }
    }

    /** The website, signed in as [account]. */
    fun web(account: String): WebActor = server.web(account)

    /** Syncs each device once, in order. */
    suspend fun syncAll(vararg devices: SyncDevice): List<SyncStatus> = devices.map { it.syncNow() }

    /**
     * Syncs [devices] in rotating order until a full round makes no POST and every run succeeds.
     * Fails after [maxRounds] rounds.
     */
    suspend fun syncUntilQuiescent(vararg devices: SyncDevice, maxRounds: Int = 4) {
        val rounds = mutableListOf<String>()
        repeat(maxRounds) { round ->
            val runs = devices.map { device ->
                device.syncNow()
                device.name to device.lastRun!!
            }
            rounds += "round ${round + 1}: " + runs.joinToString { (name, run) ->
                "$name ${run.status::class.simpleName} GET=${run.gets} POST=${run.postStatuses}"
            }
            if (runs.all { (_, run) -> run.succeeded && run.posts == 0 }) return
        }
        fail("Devices did not quiesce in $maxRounds rounds:\n${rounds.joinToString("\n")}")
    }

    /** Every device's public flows and the server's live data for [account] show the same user data. */
    suspend fun assertConverged(account: String, vararg devices: SyncDevice) {
        val expected = UserDataSnapshot.of(server.state(account))
        devices.forEach { device ->
            assertEquals(expected, device.snapshot(), "${device.name} differs from the server for $account")
        }
    }

    internal fun assertNoServerDuplicates() {
        server.accountNames.forEach { server.state(it).assertNoDuplicates() }
    }

    internal suspend fun close() {
        devices.forEach { it.shutdown() }
        server.close()
        Dispatchers.resetMain()
        main.shutdownNow()
    }
}

/** Fails unless [status] is a successful run. */
fun assertSucceeded(status: SyncStatus, message: String? = null) {
    if (status !is SyncStatus.Succeeded) fail((message?.let { "$it: " } ?: "") + "expected a successful sync, got $status")
}
