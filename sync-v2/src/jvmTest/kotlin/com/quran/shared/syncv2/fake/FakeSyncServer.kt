package com.quran.shared.syncv2.fake

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress

/**
 * An in-process stand-in for quran.com `/v1/sync`, at the network boundary only. Per account (the bearer token):
 * - GET returns one collapsed entry per changed resource with its current data, ordered by lastChangeAt, and the
 *   virtual Favorites collection on page 1; `lastMutationAt` is the account head.
 * - POST requires `lastMutationAt == head` (409 OutOfSyncError), applies the batch atomically, and echoes the request
 *   data. Errors are the backend's `{success:false, error:{code, message, details}}` wrapped in the API gateway's
 *   `{message, type, success, requestId, details}`, with `error.details.mutationIndex` when enabled.
 * Resource rules and side effects live in [FakeMutations].
 */
class FakeSyncServer : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val accounts = mutableMapOf<String, FakeAccount>()
    private var clock = 1_700_000_000_000L

    val url: String get() = "http://127.0.0.1:${server.address.port}"
    val postBodies = mutableListOf<JsonObject>()
    val postStatuses = mutableListOf<Int>()
    /** Every request in order, as "GET" or "POST". */
    val requests = mutableListOf<String>()
    var getCount = 0

    // Fault injection (server behavior, not client mocking). The defaults are production's.
    /** `error.details.mutationIndex`, which the backend sends only with quran.com-users-backend#1932: off by default. */
    @Volatile var includeMutationIndex = false
    /** Production wraps backend errors in the API gateway's envelope; false sends the backend's own shape. */
    @Volatile var gatewayErrors = true
    /** Applies the next POST, then drops the connection without a response. */
    @Volatile var loseNextPostResponse = false
    /** Like [loseNextPostResponse], for the n-th POST from now that applies (1 = the next one). */
    @Volatile var loseResponseOfPost = 0
    /** The n-th POST from now that applies commits, then answers 500 (an error after the commit). */
    @Volatile var failAfterCommitOfPost = 0
    /** Drops the next POST's connection before applying it: a request that never landed. */
    @Volatile var dropNextPost = false
    /** Rejects a mutation the way an unexpected server rule would (for example a 500 on bad data). */
    @Volatile var rejectMutation: ((JsonObject) -> HttpError?)? = null
    /** Rejects a mutation in body validation, before the head check, as a Joi rule the client doesn't know would. */
    @Volatile var rejectInValidation: ((JsonObject) -> HttpError?)? = null
    /** Runs while a POST is being processed, before it is applied: a user edit during the round trip. */
    @Volatile var duringNextPost: (() -> Unit)? = null
    /**
     * Leaves the matching answers out of the next successful POST's response, as the backend does for the creates it
     * collapses: the response is then shorter than the request.
     */
    @Volatile var dropFromNextResponse: ((JsonObject) -> Boolean)? = null
    /**
     * The backend before quran.com-users-backend#1491: writes log no `__default__` membership, so a bookmark in Favorites
     * appears in GET only as a BOOKMARK with `isInDefaultCollection`. Turned on while writing, it seeds the legacy
     * Favorites an account has from before then (the backfill never reached them).
     */
    @Volatile var legacyFavorites = false
    /** Leaves the virtual Favorites (`__default__`) out of GET, as a server without it would. */
    @Volatile var omitDefaultCollection = false
    /** Runs while a GET is being processed, before it answers. */
    @Volatile var duringNextGet: (() -> Unit)? = null
    /** Runs before answering the next GET of that page number: another device writing between pages. */
    @Volatile var beforeGetPage: Pair<Int, () -> Unit>? = null

    init {
        server.createContext("/v1/sync") { exchange ->
            try {
                synchronized(this) {
                    requests += exchange.requestMethod
                    when (exchange.requestMethod) {
                        "GET" -> handleGet(exchange)
                        "POST" -> handlePost(exchange)
                        else -> respond(exchange, 405, "{}")
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                respond(exchange, 500, errorBody(HttpError(500, "ServerError", "Something went wrong"), null))
            }
        }
        server.start()
    }

    @Synchronized
    fun account(name: String): FakeAccount = accounts.getOrPut(name) { FakeAccount(clock).also { clock += 1_000_000 } }

    /** Another device: applies mutations directly at the current head and returns their resource ids. */
    @Synchronized
    fun remote(name: String, vararg mutations: JsonObject): List<String?> {
        val mutationRules = FakeMutations(account(name), legacyFavorites)
        return mutations.map { mutation ->
            val resource = mutation.str("resource")!!
            val type = mutation.str("type")!!
            val resourceId = mutation.str("resourceId")
            val data = mutationRules.validate(resource, type, resourceId, mutation["data"] as? JsonObject)
            mutationRules.apply(resource, type, resourceId, data)
        }
    }

    private fun handleGet(exchange: HttpExchange) {
        getCount += 1
        duringNextGet?.let { duringNextGet = null; it() }
        val account = account(bearer(exchange))
        val query = exchange.requestURI.query.orEmpty().split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }
        val since = query.getValue("mutationsSince").toLong()
        val page = query["page"]?.toInt() ?: 1
        beforeGetPage?.takeIf { it.first == page }?.let { beforeGetPage = null; it.second() }
        val limit = query["limit"]?.toInt() ?: 100
        val includeDefault = account.defaultCollectionAt > since && !omitDefaultCollection
        val rows = account.logs.values.filter { it.lastChangeAt > since }.sortedBy { it.lastChangeAt }
        val all = (if (includeDefault) listOf(null) else emptyList()) + rows
        val body = buildJsonObject {
            put("success", true)
            put("data", buildJsonObject {
                put("lastMutationAt", account.head)
                put("page", page)
                put("limit", limit)
                put("total", all.size)
                put("hasMore", page * limit < all.size)
                put("mutations", buildJsonArray {
                    all.drop((page - 1) * limit).take(limit).forEach { row ->
                        add(if (row == null) defaultCollection(account) else entry(account, row))
                    }
                })
            })
        }
        respond(exchange, 200, body.toString())
    }

    private fun defaultCollection(account: FakeAccount) = buildJsonObject {
        put("resource", "COLLECTION")
        put("resourceId", FakeAccount.DEFAULT_ID)
        put("type", "CREATE")
        put("data", buildJsonObject {
            put("name", "Favorites"); put("isDefault", true); put("isSystem", true)
        })
        put("timestamp", account.defaultCollectionAt)
    }

    private fun entry(account: FakeAccount, row: LogRow) = buildJsonObject {
        put("resource", row.resource)
        if (row.resource != "COLLECTION_BOOKMARK") put("resourceId", row.id)
        val deleted = row.deletedAt != null
        val updated = !deleted && (row.updatedAt ?: 0) > row.createdAt
        put("type", if (deleted) "DELETE" else if (updated) "UPDATE" else "CREATE")
        put("data", when {
            row.resource == "COLLECTION_BOOKMARK" -> account.dataOf(row).let { data ->
                if (deleted) JsonObject(data.filterKeys { it == "collectionId" || it == "bookmarkId" }) else data
            }
            deleted -> JsonObject(emptyMap())
            else -> account.dataOf(row)
        })
        put("timestamp", row.lastChangeAt)
    }

    private fun handlePost(exchange: HttpExchange) {
        val account = account(bearer(exchange))
        val expected = Regex("lastMutationAt=(-?\\d+)").find(exchange.requestURI.query.orEmpty())?.groupValues?.get(1)?.toLong()
        val body = json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
        postBodies += body
        if (dropNextPost) {
            dropNextPost = false
            postStatuses += -1
            exchange.close()
            return
        }
        duringNextPost?.let { duringNextPost = null; it() }
        val mutations = body["mutations"]!!.jsonArray.map { it.jsonObject }
        val rules = FakeMutations(account, legacyFavorites)
        // As the backend's middleware order: body validation (422), then the head check (409) in the transaction.
        if (mutations.size > MAX_MUTATIONS) {
            fail(exchange, HttpError(422, "ValidationError", "Mutations must not exceed $MAX_MUTATIONS"), null)
            return
        }
        val validated = mutations.mapIndexed { i, mutation ->
            try {
                rejectInValidation?.invoke(mutation)?.let { throw it }
                rules.validate(mutation.str("resource")!!, mutation.str("type")!!, mutation.str("resourceId"), mutation["data"] as? JsonObject)
            } catch (error: HttpError) {
                fail(exchange, error, i)
                return
            }
        }
        if (expected != account.head) {
            fail(exchange, HttpError(409, "OutOfSyncError", "Invalid lastMutationAt, please re-sync your data and try again."), null)
            return
        }
        val snapshot = account.snapshot()
        var index = 0
        try {
            // Errors name the request index; the response lists only the mutations that were applied.
            val processed = FakeReadingBookmarks.effectiveIndices(mutations).map { i ->
                val mutation = mutations[i]
                index = i
                rejectMutation?.invoke(mutation)?.let { throw it }
                val resource = mutation.str("resource")!!
                val type = mutation.str("type")!!
                val resourceId = mutation.str("resourceId")
                // The response echoes the request data as Joi left it, not the stored state.
                val data = validated[i]
                val id = rules.apply(resource, type, resourceId, data)
                buildJsonObject {
                    put("type", type)
                    put("resource", resource)
                    put("resourceId", id ?: "")
                    put("data", data)
                    put("timestamp", account.head)
                }
            }
            if (loseResponseOfPost > 0 && --loseResponseOfPost == 0) loseNextPostResponse = true
            if (failAfterCommitOfPost > 0 && --failAfterCommitOfPost == 0) {
                fail(exchange, HttpError(500, "ServerError", "Something went wrong"), 0)
                return
            }
            if (loseNextPostResponse) {
                loseNextPostResponse = false
                postStatuses += -1
                exchange.close()
                return
            }
            postStatuses += 200
            val answered = dropFromNextResponse?.let { drop -> dropFromNextResponse = null; processed.filterNot(drop) } ?: processed
            respond(exchange, 200, buildJsonObject {
                put("success", true)
                put("data", buildJsonObject {
                    put("lastMutationAt", account.head)
                    put("mutations", JsonArray(answered))
                })
            }.toString())
        } catch (error: HttpError) {
            account.restore(snapshot)
            fail(exchange, error, index)
        }
    }

    private fun fail(exchange: HttpExchange, error: HttpError, index: Int?) {
        postStatuses += error.status
        respond(exchange, error.status, errorBody(error, index.takeIf { includeMutationIndex && error.code != "OutOfSyncError" }))
    }

    private fun errorBody(error: HttpError, index: Int?): String {
        val backend = buildJsonObject {
            put("success", false)
            put("error", buildJsonObject {
                put("code", error.code)
                put("message", error.message)
                if (error.details.isNotEmpty() || index != null) {
                    put("details", buildJsonObject {
                        error.details.forEach { (key, value) -> put(key, value) }
                        index?.let { put("mutationIndex", it) }
                    })
                }
            })
        }
        // An empty code is the gateway's own error (routing, maintenance, rate limit): no backend body at all.
        if (error.code.isEmpty()) return buildJsonObject { put("message", error.message); put("success", false) }.toString()
        if (!gatewayErrors) return backend.toString()
        return buildJsonObject {
            put("message", error.message)
            put("type", "error")
            put("success", false)
            put("requestId", "req-${postStatuses.size}")
            put("details", backend)
        }.toString()
    }

    private fun bearer(exchange: HttpExchange): String =
        exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ") ?: error("no auth")

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
    }

    private companion object {
        const val MAX_MUTATIONS = 100
    }
}
