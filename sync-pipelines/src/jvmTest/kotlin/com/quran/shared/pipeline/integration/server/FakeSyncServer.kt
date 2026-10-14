package com.quran.shared.pipeline.integration.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

/** One request the server received, for assertions. [status] is -1 when the connection was dropped. */
data class RequestRecord(
    val method: String,
    val device: String?,
    val account: String?,
    val query: Map<String, String>,
    val body: JsonObject?,
    val status: Int,
    val response: String?
) {
    val mutations: List<JsonObject> get() = (body?.get("mutations") as? JsonArray)?.map { it as JsonObject }.orEmpty()
}

/**
 * An in-process stand-in for `quran.com-users-backend` `GET`/`POST /v1/sync` on loopback HTTP,
 * shared by every simulated device. It fakes the network boundary only: devices talk to it through
 * the production Ktor/OkHttp client.
 *
 * It mirrors `apps/api/src/modules/v1/sync/` and the services it calls (see [CollectionRules],
 * [NoteSessionRules], [MutationValidation] and [ResourceRendering]): per-user `ResourceLog` and
 * head, the virtual Favorites collection, POST check order (query, body validation, scopes, head),
 * atomic POSTs, echoes of the validated request data, and errors in the API gateway's envelope.
 * [faults] injects failures; [web] writes like the website does.
 */
class FakeSyncServer : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val http: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool { Thread(it, "fake-sync-server").apply { isDaemon = true } }
    private val ids = IdSource()
    private val accounts = LinkedHashMap<String, FakeAccount>()
    private val tokens = LinkedHashMap<String, Token>()
    private val log = mutableListOf<RequestRecord>()
    private var requestCounter = 0

    /** Guards all server state. Web writes and fault callbacks take it too. */
    internal val lock = Any()

    val clock = FakeServerClock()
    val faults = ServerFaults()

    /** The website, signed in as [account]. */
    fun web(account: String): WebActor = WebActor(this, account)

    val url: String get() = "http://127.0.0.1:${http.address.port}"

    init {
        http.executor = executor
        http.createContext("/v1/sync") { exchange -> handle(exchange) }
        http.start()
    }

    // region Accounts and tokens
    private data class Token(val account: String, val device: String, var expired: Boolean = false)

    /** The account named [name], created with `createdAt = now` on first use. */
    fun account(name: String): FakeAccount = synchronized(lock) {
        accounts.getOrPut(name) { FakeAccount(name, clock.now(), clock, ids) }
    }

    /** Issues an access token for [account] on [device]. The fake auth layer calls this at login. */
    fun issueToken(account: String, device: String): String = synchronized(lock) {
        account(account)
        "token-${ids.next("")}-$device".also { tokens[it] = Token(account, device) }
    }

    /** Expires every token of [account]: the next request with one gets 401 `TokenExpiredError`. */
    fun expireTokens(account: String) = synchronized(lock) { tokens.values.filter { it.account == account }.forEach { it.expired = true } }

    internal fun rulesFor(account: FakeAccount) = AccountRules(account) { id -> ownerOf(id) }

    private fun ownerOf(id: String): String? = accounts.values.firstOrNull { account ->
        id in account.collections || id in account.bookmarks || id in account.notes ||
            id in account.sessions || id in account.readingBookmarks
    }?.name
    // endregion

    // region Inspection
    /** Every request so far, in arrival order. */
    val requests: List<RequestRecord> get() = synchronized(log) { log.toList() }

    fun requests(device: String): List<RequestRecord> = requests.filter { it.device == device }

    /** The account's live data and log, copied. */
    fun state(account: String): ServerState = synchronized(lock) { ServerState.of(account(account)) }

    val accountNames: Set<String> get() = synchronized(lock) { accounts.keys.toSet() }

    fun head(account: String): Long = synchronized(lock) { account(account).head }

    fun resetRequestLog() = synchronized(log) { log.clear() }
    // endregion

    private fun handle(exchange: HttpExchange) {
        val query = parseQuery(exchange.requestURI.rawQuery)
        val bodyText = if (exchange.requestMethod == "POST") exchange.requestBody.readBytes().decodeToString() else null
        val body = bodyText?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        val token = exchange.requestHeaders.getFirst("x-auth-token")
            ?: exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
        val tokenInfo = synchronized(lock) { token?.let(tokens::get) }
        val request = FakeRequest(
            exchange.requestMethod, tokenInfo?.account, tokenInfo?.device, query,
            ((body as? JsonObject)?.get("mutations") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        )
        fun record(status: Int, response: String?) = synchronized(log) {
            log += RequestRecord(request.method, request.device, request.account, query, body as? JsonObject, status, response)
        }
        try {
            if (faults.take<ServerFaults.Action.Drop>(request) != null) {
                record(-1, null)
                exchange.close()
                return
            }
            faults.take<ServerFaults.Action.Block>(request)?.gate?.hold()
            faults.take<ServerFaults.Action.Fail>(request)?.let { throw it.error }
            faults.take<ServerFaults.Action.Malform>(request)?.let { malformed ->
                val text = when (malformed.kind) {
                    ServerFaults.Malformed.INVALID_JSON -> "{\"success\":true,\"data\":"
                    ServerFaults.Malformed.SUCCESS_FALSE_200 -> "{\"success\":false}"
                }
                record(200, text)
                respond(exchange, 200, text)
                return
            }
            val account = authenticate(tokenInfo)
            val (status, response, head) = when (exchange.requestMethod) {
                "GET" -> Triple(200, synchronized(lock) { handleGet(account, request) }, null)
                "POST" -> handlePost(account, request, body, exchange.requestHeaders.getFirst("x-client-id") ?: "unknown-client")
                else -> throw HttpError(404, "NotFoundError", "Not found")
            }
            val lost = if (request.isPost) faults.take<ServerFaults.Action.Lose>(request) else null
            when (lost?.how) {
                LostResponse.CONNECTION_DROPPED -> {
                    record(-1, response)
                    exchange.close()
                }
                LostResponse.GATEWAY_TIMEOUT -> {
                    val error = HttpError(504, "GatewayTimeoutError", "Endpoint request timed out")
                    errorBody(error).let { record(504, it); respond(exchange, 504, it) }
                }
                null -> {
                    record(status, response)
                    respond(exchange, status, response, head ?: synchronized(lock) { request.account?.let { account(it).head } })
                }
            }
        } catch (error: HttpError) {
            errorBody(error).let { record(error.status, it); respond(exchange, error.status, it) }
        } catch (error: Exception) {
            error.printStackTrace()
            errorBody(HttpError(500, "ServerError", "Something went wrong")).let { record(500, it); respond(exchange, 500, it) }
        }
    }

    private fun authenticate(token: Token?): FakeAccount {
        token ?: throw forbidden()
        if (token.expired) throw HttpError(401, "TokenExpiredError", "Token expired")
        return account(token.account)
    }

    // region GET
    private fun handleGet(account: FakeAccount, request: FakeRequest): String {
        val query = request.query
        val since = query["mutationsSince"]?.toLongOrNull() ?: throw validationError("\"mutationsSince\" is required")
        val page = (query["page"]?.toIntOrNull() ?: 1).also { if (it < 1) throw validationError("\"page\" must be greater than or equal to 1") }
        val requestedLimit = query["limit"]?.toIntOrNull() ?: 100
        if (requestedLimit !in 1..1000) throw validationError("\"limit\" must be less than or equal to 1000")
        val requested = query["resources"]?.split(",")?.filter { it.isNotEmpty() }.orEmpty()
        requested.filter { it !in RESOURCE_SCOPES }.takeIf { it.isNotEmpty() }?.let {
            throw validationError("Invalid resources: ${it.joinToString(", ")}. Valid resources are: ${RESOURCE_SCOPES.keys.joinToString(", ")}")
        }
        faults.takeRuns(request).forEach { it() }
        val limit = faults.pageSizeCap?.let { minOf(it, requestedLimit) } ?: requestedLimit

        val excluded = RESOURCE_SCOPES.filter { (_, scopes) -> !account.scopes.containsAll(scopes) }.keys
        val includesCollections = "COLLECTION" !in excluded && (requested.isEmpty() || "COLLECTION" in requested)
        val defaultBelongs = includesCollections && account.createdAt > since
        val includeDefault = page == 1 && defaultBelongs
        val resourceLimit = if (includeDefault) limit - 1 else limit
        val offset = if (page == 1) 0 else (page - 1) * limit - (if (defaultBelongs) 1 else 0)
        val rows = account.logs.values
            .filter { it.lastChangeAt > since && it.resource !in excluded && (requested.isEmpty() || it.resource in requested) }
            .sortedWith(compareBy<LogRow> { it.lastChangeAt }.thenBy { it.seq })
        val total = rows.size + if (defaultBelongs) 1 else 0
        val mutations = buildList {
            if (includeDefault) add(ResourceRendering.defaultCollection(account))
            rows.drop(offset).take(resourceLimit).forEach { row ->
                add(ResourceRendering.entry(account, row, faults.takeVanished("${row.resource}:${row.resourceId}")))
            }
        }
        return success(buildJsonObject {
            put("lastMutationAt", account.head)
            put("mutations", JsonArray(mutations))
            put("page", page)
            put("limit", limit)
            put("total", total)
            put("hasMore", page.toLong() * limit < total)
        })
    }
    // endregion

    // region POST
    private fun handlePost(account: FakeAccount, request: FakeRequest, body: JsonElement?, clientId: String): Triple<Int, String, Long> {
        val lastMutationAt = request.query["lastMutationAt"]
            ?: throw HttpError(422, "ValidationError", "\"lastMutationAt\" is required", buildJsonObject { put("lastMutationAt", "MISSING") })
        val expected = lastMutationAt.toLongOrNull() ?: throw validationError("\"lastMutationAt\" must be a number")
        // Route middleware: body validation, then scopes. Both run before the head check.
        val mutations = MutationValidation.validateBody(body)
        val required = mutations.flatMap { RESOURCE_SCOPES[it.resource].orEmpty() }.toSet()
        if (!account.scopes.containsAll(required)) throw forbidden()
        faults.takeRuns(request).forEach { synchronized(lock) { it() } }
        return synchronized(lock) {
            if (expected != account.head) {
                throw HttpError(409, "OutOfSyncError", "Invalid lastMutationAt, please re-sync your data and try again.")
            }
            val snapshot = account.snapshot()
            val applier = MutationApplier(rulesFor(account), clientId)
            var current: ValidatedMutation? = null
            try {
                val effective = MutationApplier.collapseReadingBookmarkCreates(mutations)
                val rejection = faults.rejection(effective)
                val processed = effective.map { mutation ->
                    current = mutation
                    if (rejection?.first === mutation) throw rejection.second
                    val operationTime = clock.now()
                    val resourceId = applier.apply(mutation)
                    Processed(mutation, resourceId, operationTime)
                }
                val response = success(buildJsonObject {
                    put("lastMutationAt", account.head)
                    put("mutations", JsonArray(processed.map { it.render(account) }))
                })
                Triple(200, response, account.head)
            } catch (error: HttpError) {
                account.restore(snapshot)
                throw error.also { it.mutationIndex = current?.index }
            } catch (error: Exception) {
                // An unexpected error is the same 500 ServerError; BE1 still reports its mutation.
                account.restore(snapshot)
                throw HttpError(500, "ServerError", "Something went wrong", mutationIndex = current?.index)
                    .also { it.initCause(error) }
            }
        }
    }

    private class Processed(val mutation: ValidatedMutation, val resourceId: String?, val operationTime: Long) {
        /** The response echoes the validated request data; the timestamp is the log's `lastChangeAt`. */
        fun render(account: FakeAccount) = buildJsonObject {
            put("type", mutation.type)
            put("resource", mutation.resource)
            mutation.data?.let { put("data", it) }
            resourceId?.let { put("resourceId", it) }
            val row = resourceId?.takeIf { it.isNotEmpty() }?.let { account.logRow(mutation.resource, it) }
            put("timestamp", row?.lastChangeAt ?: operationTime)
        }
    }
    // endregion

    // region Rendering
    private fun success(data: JsonObject): String = buildJsonObject {
        put("success", true)
        put("data", data)
    }.toString()

    /**
     * The API gateway's envelope around the backend's `{success, error:{code, message, details}}`.
     * In BE1 mode, `mutationIndex` is merged into the backend's `error.details`.
     */
    internal fun errorBody(error: HttpError): String {
        val index = error.mutationIndex.takeIf { faults.mutationIndex && error.code != "OutOfSyncError" }
        val details = when {
            index != null -> JsonObject(error.details.orEmpty() + ("mutationIndex" to kotlinx.serialization.json.JsonPrimitive(index)))
            else -> error.details
        }
        return buildJsonObject {
            put("message", error.message)
            put("type", GATEWAY_TYPES[error.status] ?: "error")
            put("success", false)
            put("requestId", "request-${synchronized(log) { ++requestCounter }}")
            put("details", buildJsonObject {
                put("success", false)
                put("error", buildJsonObject {
                    put("code", error.code)
                    put("message", error.message)
                    details?.let { put("details", it) }
                })
            })
        }.toString()
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, head: Long? = null) {
        val bytes = body.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        head?.let { exchange.responseHeaders.add("X-Mutation-At", it.toString()) }
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun parseQuery(raw: String?): Map<String, String> = raw.orEmpty().split("&").filter { it.isNotEmpty() }.associate {
        val parts = it.split("=", limit = 2)
        URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8)
    }
    // endregion

    override fun close() {
        http.stop(0)
        executor.shutdownNow()
    }

    companion object {
        /** `RESOURCE_TO_SCOPES_MAP`. */
        val RESOURCE_SCOPES = mapOf(
            "BOOKMARK" to setOf("bookmark"),
            "COLLECTION" to setOf("collection"),
            "COLLECTION_BOOKMARK" to setOf("bookmark", "collection"),
            "NOTE" to setOf("note"),
            "READING_BOOKMARK" to setOf("bookmark"),
            "READING_SESSION" to setOf("reading_session")
        )

        private val GATEWAY_TYPES = mapOf(
            401 to "unauthorized", 403 to "forbidden", 404 to "not_found", 409 to "conflict",
            422 to "unprocessable_entity", 500 to "internal_server_error", 503 to "service_unavailable",
            504 to "gateway_timeout"
        )
    }
}
