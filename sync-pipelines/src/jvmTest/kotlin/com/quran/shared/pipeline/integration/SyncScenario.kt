@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.quran.shared.pipeline.integration

import com.quran.shared.pipeline.integration.server.FakeSyncServer
import com.quran.shared.pipeline.integration.server.ServerDuplicate
import com.quran.shared.pipeline.integration.server.WebActor
import com.quran.shared.pipeline.integration.server.duplicatesMessage
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
 *   is offline. [SyncDevice.syncNow] calls `triggerSync(immediately = true)`, waits on
 *   `syncStatus` for the run's end, retries included, and returns a [SyncRun] with its requests.
 *   [syncAll] syncs devices in order; [syncUntilQuiescent] repeats until a round changes nothing.
 * - **Accounts.** [SyncDevice.login] signs in through [FakeAuthRepository] and waits for the sync
 *   the login starts. [SyncDevice.auth] can expire the session or rotate the token.
 * - **The web.** [web] writes straight into the server with REST semantics, also between a
 *   device's GET and POST through [FakeSyncServer.faults] (`beforePost`, `betweenPages`).
 * - **Faults.** `server.faults` fails, blocks or loses requests, rejects chosen mutations, and
 *   turns on the backend's `mutationIndex` (quran.com-users-backend#1932).
 * - **Known issues.** Wrap a test that fails today in [knownIssue] with its tracker id, a
 *   one-line description, and an `expect` predicate that pins how it fails today.
 * - **Time.** Pass explicit `PlatformDateTime` timestamps for anything compared by time, and move
 *   the server's clock with `server.clock`.
 *
 * After the block, every account on the server is checked for duplicates
 * ([com.quran.shared.pipeline.integration.server.ServerState.duplicates]):
 * - Duplicates a scenario expects by design are excluded with [SyncScenario.allowDuplicates].
 * - Duplicates an open issue leaves are pinned with [knownDuplicates]: the ones its matcher accepts
 *   are checked inside [knownIssue], so the scenario fails once they are gone.
 * - Any other duplicate fails the scenario.
 *
 * The scenario also fails when the fake server hit an error that models no backend behavior.
 */
fun syncScenario(knownDuplicates: KnownDuplicates? = null, block: suspend SyncScenario.() -> Unit): Unit = runBlocking {
    val scenario = SyncScenario()
    var failure: Throwable? = null
    try {
        scenario.block()
        scenario.checkServerDuplicates(knownDuplicates)
    } catch (error: Throwable) {
        failure = error
    }
    val bugs = scenario.close()
    if (bugs.isNotEmpty()) {
        throw AssertionError("The fake server hit ${bugs.size} error(s) that model no backend behavior: ${bugs.first().message}", bugs.first())
            .apply { (bugs.drop(1) + listOfNotNull(failure)).forEach(::addSuppressed) }
    }
    if (failure != null) throw failure
}

/**
 * Duplicates an open issue leaves on the server: [match] accepts each duplicate the issue causes.
 * Any duplicate it rejects still fails the scenario.
 */
class KnownDuplicates(val id: String, val description: String, val match: (ServerDuplicate) -> Boolean) {
    init {
        KnownIssue(id, description)
    }
}

/** Part of [duplicatesMessage]'s failure message. */
private const val DUPLICATES_FAILURE = "has duplicates"

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
    suspend fun syncAll(vararg devices: SyncDevice): List<SyncRun> = devices.map { it.syncNow() }

    /**
     * Syncs [devices] round by round until a round in which every run succeeded (none skipped),
     * none POSTed, and no account's server head moved. Each round starts one device later than
     * the previous one, so the order rotates. Fails after [maxRounds] rounds; when the last round
     * had a failed or skipped run, the failure is a [SyncRunFailure] carrying it, so [knownIssue]
     * can pin it with [syncRejectedWith] or [syncFailedLocally].
     */
    suspend fun syncUntilQuiescent(vararg devices: SyncDevice, maxRounds: Int = 4) {
        require(devices.isNotEmpty()) { "Pass at least one device" }
        val rounds = mutableListOf<String>()
        var lastFailed: SyncRun? = null
        repeat(maxRounds) { round ->
            val order = devices.indices.map { devices[(it + round) % devices.size] }
            val heads = heads()
            val runs = order.map { it.name to it.syncNow() }
            val headsAfter = heads()
            rounds += "round ${round + 1}: " + runs.joinToString { (name, run) ->
                "$name ${run.status?.let { it::class.simpleName } ?: "skipped"} GET=${run.gets} POST=${run.postStatuses}"
            } + if (heads != headsAfter) " (head moved)" else ""
            if (heads == headsAfter && runs.all { (_, run) -> run.succeeded && run.posts == 0 }) return
            lastFailed = runs.lastOrNull { (_, run) -> !run.succeeded }?.second
        }
        val message = "Devices did not quiesce in $maxRounds rounds:\n${rounds.joinToString("\n")}"
        lastFailed?.let { throw SyncRunFailure(it, message) }
        fail(message)
    }

    private fun heads(): Map<String, Long> = server.accountNames.associateWith { server.head(it) }

    /** Every device's public flows and the server's live data for [account] show the same user data. */
    suspend fun assertConverged(account: String, vararg devices: SyncDevice) {
        val expected = UserDataSnapshot.of(server.state(account))
        devices.forEach { device ->
            assertEquals(expected, device.snapshot(), "${device.name} differs from the server for $account")
        }
    }

    private class Allowance(val reason: String, val match: (ServerDuplicate) -> Boolean)

    private val allowances = mutableListOf<Allowance>()

    /**
     * Declares duplicates that are expected by design, not by a bug: the final duplicate check
     * skips each one [match] accepts. It is not strict: nothing fails when no such duplicate shows
     * up. [match] runs after the scenario's block, so it can read the server's final state. For
     * duplicates an open issue causes, use `syncScenario(knownDuplicates = …)` instead.
     */
    fun allowDuplicates(reason: String, match: (ServerDuplicate) -> Boolean) {
        require(reason.isNotBlank()) { "Say why the duplicates are expected" }
        allowances += Allowance(reason, match)
    }

    /** The server's duplicates on every account, without the allowed ones. */
    fun serverDuplicates(): List<ServerDuplicate> =
        server.accountNames.flatMap { server.state(it).duplicates() }.filterNot { duplicate -> allowances.any { it.match(duplicate) } }

    /** Fails when any account on the server holds duplicates that are not allowed. */
    fun assertNoServerDuplicates() {
        val found = serverDuplicates()
        if (found.isNotEmpty()) throw AssertionError(duplicatesMessage(found))
    }

    /** Fails on unexpected duplicates, and pins the ones [known] accepts. */
    internal suspend fun checkServerDuplicates(known: KnownDuplicates?) {
        val (expected, unexpected) = serverDuplicates().partition { known?.match?.invoke(it) == true }
        if (unexpected.isNotEmpty()) throw AssertionError(duplicatesMessage(unexpected))
        if (known == null) return
        knownIssue(known.id, known.description, expect = { DUPLICATES_FAILURE in it.message.orEmpty() }) {
            if (expected.isNotEmpty()) throw AssertionError(duplicatesMessage(expected))
        }
    }

    /**
     * Shuts everything down and returns the fake server's internal errors, read only after the
     * server released its held requests and every handler finished.
     */
    internal suspend fun close(): List<Throwable> {
        try {
            devices.forEach { it.shutdown() }
        } finally {
            server.close()
            Dispatchers.resetMain()
            main.shutdownNow()
        }
        return server.internalErrors
    }
}
