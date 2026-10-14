package com.quran.shared.pipeline.integration.server

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A request as fault rules see it, before the server handles it. */
class FakeRequest(
    val method: String,
    val account: String?,
    val device: String?,
    val query: Map<String, String>,
    /** The raw POSTed mutations; empty for GET. */
    val mutations: List<JsonObject>
) {
    val isPost: Boolean get() = method == "POST"

    /** Whether this POST carries a mutation of [resource], optionally of [type]. */
    fun carries(resource: String, type: String? = null): Boolean = mutations.any {
        it.text("resource") == resource && (type == null || it.text("type") == type)
    }
}

/** How a lost response looks to the client. */
enum class LostResponse {
    /** The API gateway gave up waiting and answered 504, but the backend committed. Deterministic. */
    GATEWAY_TIMEOUT,

    /** The connection closed without a response. OkHttp may transparently retry the POST, as it would in production. */
    CONNECTION_DROPPED
}

/** Holds matching requests at the server until [release]. */
class RequestGate internal constructor() {
    private val arrived = CountDownLatch(1)
    private val released = CountDownLatch(1)

    /** Waits until a held request has reached the server. */
    fun awaitArrived(timeoutMillis: Long = 10_000) {
        check(arrived.await(timeoutMillis, TimeUnit.MILLISECONDS)) { "No request reached the gate within $timeoutMillis ms" }
    }

    fun release() = released.countDown()

    internal fun hold() {
        arrived.countDown()
        check(released.await(30, TimeUnit.SECONDS)) { "A held request was never released" }
    }
}

/**
 * Fault injection at the server. Each rule matches requests (or mutations), fires [times] times
 * ([ALWAYS] for every match), and is consumed in registration order. Faults act at the network
 * boundary only: they change what the server does or answers, never the client.
 */
class ServerFaults internal constructor() {
    /** Errors caused by one mutation carry `details.mutationIndex` (quran.com-users-backend#1932). Off, as in production. */
    @Volatile var mutationIndex: Boolean = false

    /**
     * Serves at most this many entries per GET page, whatever `limit` the client asked for. The
     * real server honours the client's limit up to 1000. Today's client derives the page count
     * from its own 1000 limit, so a cap below that makes it skip pages; use it only for engines that
     * page by `hasMore` or `limit`.
     */
    @Volatile var pageSizeCap: Int? = null

    private val rules = mutableListOf<Rule>()
    private val vanished = mutableSetOf<String>()

    /** Answers matching requests with an error before applying anything. */
    fun failRequests(
        times: Int = 1,
        status: Int = 500,
        code: String = "ServerError",
        message: String = "Something went wrong",
        details: JsonObject? = JsonObject(emptyMap()),
        match: (FakeRequest) -> Boolean = { it.isPost }
    ) = add(times, match, Action.Fail(HttpError(status, code, message, details)))

    /** An outage: the next [requests] requests of any kind get [status]. */
    fun outage(requests: Int, status: Int = 503) =
        failRequests(requests, status, "ServiceUnavailableError", "Service unavailable") { true }

    /**
     * Drops every connection without an answer until [networkUp]: the device sees a network error
     * on each attempt, OkHttp's own retries included. There is no finite variant, because OkHttp may
     * retry a dropped request by itself; use [failRequests], [outage] or [loseResponse] instead.
     */
    fun networkDown() {
        networkDownRule = add(ALWAYS, { true }, Action.Drop)
    }

    fun networkUp() = synchronized(rules) { networkDownRule?.let(rules::remove); networkDownRule = null }

    private var networkDownRule: Rule? = null

    /** Commits matching POSTs, then loses the response, by default deterministically as a gateway 504. */
    fun loseResponse(
        times: Int = 1,
        how: LostResponse = LostResponse.GATEWAY_TIMEOUT,
        match: (FakeRequest) -> Boolean = { it.isPost }
    ) = add(times, match, Action.Lose(how))

    /** Holds the next matching POST before it is applied, until the returned gate is released. */
    fun blockPost(times: Int = 1, match: (FakeRequest) -> Boolean = { true }): RequestGate =
        RequestGate().also { gate ->
            synchronized(gates) { gates += gate }
            add(times, { it.isPost && match(it) }, Action.Block(gate))
        }

    private val gates = mutableListOf<RequestGate>()

    /** Releases every gate, so held requests finish before the server stops. */
    internal fun releaseGates() = synchronized(gates) { gates.forEach(RequestGate::release) }

    /**
     * Rejects a POST at the first mutation matching [match], inside the transaction, so the whole
     * batch rolls back. With [mutationIndex] on, the error carries that mutation's index.
     */
    fun rejectMutation(
        times: Int = ALWAYS,
        status: Int = 422,
        code: String = "ValidationError",
        message: String = "Rejected by test",
        details: JsonObject? = JsonObject(emptyMap()),
        match: (ValidatedMutation) -> Boolean
    ) = add(times, { true }, Action.Reject(match, HttpError(status, code, message, details)))

    /** Runs [action] (typically a web write) after the client's GET and before its POST is checked against the head. */
    fun beforePost(times: Int = 1, action: () -> Unit) = add(times, { it.isPost }, Action.Run(action))

    /** Runs [action] before the server answers a GET for page 2 or later. */
    fun betweenPages(times: Int = 1, action: () -> Unit) =
        add(times, { !it.isPost && (it.query["page"]?.toIntOrNull() ?: 1) > 1 }, Action.Run(action))

    /** Answers matching requests with a body the client cannot use, without applying anything. */
    fun malformedResponse(times: Int = 1, kind: Malformed = Malformed.INVALID_JSON, match: (FakeRequest) -> Boolean = { true }) =
        add(times, match, Action.Malform(kind))

    /** The next GET that reports [resource] [resourceId] sends it as CREATE with `{}`: a log row whose entity is gone. */
    fun vanishOnce(resource: String, resourceId: String) = synchronized(vanished) { vanished += "$resource:$resourceId" }

    fun clear() {
        synchronized(rules) { rules.clear(); networkDownRule = null }
        synchronized(vanished) { vanished.clear() }
        mutationIndex = false
        pageSizeCap = null
    }

    enum class Malformed { INVALID_JSON, SUCCESS_FALSE_200 }

    internal sealed interface Action {
        class Fail(val error: HttpError) : Action
        data object Drop : Action
        class Lose(val how: LostResponse) : Action
        class Block(val gate: RequestGate) : Action
        class Reject(val match: (ValidatedMutation) -> Boolean, val error: HttpError) : Action
        class Run(val action: () -> Unit) : Action
        class Malform(val kind: Malformed) : Action
    }

    /** A registered fault; [remaining] counts down as it fires. */
    class Rule internal constructor(var remaining: Int, internal val match: (FakeRequest) -> Boolean, internal val action: Action)

    private fun add(times: Int, match: (FakeRequest) -> Boolean, action: Action): Rule {
        require(times > 0) { "times must be positive" }
        return Rule(times, match, action).also { synchronized(rules) { rules += it } }
    }

    /** Consumes and returns the first matching rule whose action is of type [T]. */
    internal inline fun <reified T : Action> take(request: FakeRequest): T? = synchronized(rules) {
        val rule = rules.firstOrNull { it.action is T && it.match(request) } ?: return null
        consume(rule)
        rule.action as T
    }

    /** Consumes every matching [Action.Run] rule and returns their actions. */
    internal fun takeRuns(request: FakeRequest): List<() -> Unit> = synchronized(rules) {
        rules.filter { it.action is Action.Run && it.match(request) }.map { consume(it); (it.action as Action.Run).action }
    }

    /** The first [Action.Reject] rule that matches one of [mutations], with the mutation it matched. */
    internal fun rejection(mutations: List<ValidatedMutation>): Pair<ValidatedMutation, HttpError>? = synchronized(rules) {
        for (rule in rules) {
            val action = rule.action as? Action.Reject ?: continue
            val hit = mutations.firstOrNull(action.match) ?: continue
            consume(rule)
            return hit to HttpError(action.error.status, action.error.code, action.error.message, action.error.details)
        }
        null
    }

    internal fun takeVanished(key: String): Boolean = synchronized(vanished) { vanished.remove(key) }

    private fun consume(rule: Rule) {
        if (rule.remaining != ALWAYS && --rule.remaining == 0) rules.remove(rule)
    }

    companion object {
        const val ALWAYS = Int.MAX_VALUE
    }
}
