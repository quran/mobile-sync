package com.quran.shared.syncengine.network

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.Mutation
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A sync error body. The backend sends `{success, error}`; the API gateway wraps that body in
 * `details` and adds its own `message` and `type`.
 */
@Serializable
internal data class SyncErrorResponse(
    val message: String? = null,
    val type: String? = null,
    val success: Boolean,
    val error: SyncErrorDetail? = null,
    val details: SyncErrorDetails? = null
) {
    val backendError: SyncErrorDetail? get() = error ?: details?.error
}

@Serializable
internal data class SyncErrorDetails(
    val success: Boolean,
    val error: SyncErrorDetail
)

@Serializable
internal data class SyncErrorDetail(
    val code: String,
    val message: String,
    val details: JsonElement? = null
)

private val errorResponseJson = Json {
    ignoreUnknownKeys = true
}

internal fun String.asMutation(logger: Logger): Mutation {
    return when (this) {
        "CREATE" -> Mutation.CREATED
        "UPDATE" -> Mutation.MODIFIED
        "DELETE" -> Mutation.DELETED
        else -> {
            logger.e { "Unknown mutation type: $this" }
            throw IllegalArgumentException("Unknown mutation type: $this")
        }
    }
}

internal suspend fun HttpResponse.processError(
    logger: Logger,
    logContext: SyncRequestLogContext
) {
    val errorBody = bodyAsText()
    logger.e {
        logContext.format("HTTP error response: status=${status}, body=$errorBody")
    }

    val response = try {
        errorResponseJson.decodeFromString<SyncErrorResponse>(errorBody)
    } catch (e: Exception) {
        logger.w {
            logContext.format("Failed to parse error response, using raw body: ${e.message}")
        }
        null
    }

    throw SyncNetworkException(
        status = status,
        rawBody = errorBody,
        parsedMessage = response?.message ?: response?.backendError?.message,
        serverCode = response?.backendError?.code
    )
}
