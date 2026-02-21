package com.quran.shared.syncengine.network

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.Mutation
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import io.ktor.client.call.body

@Serializable
internal data class SyncErrorResponse(
    val message: String,
    val type: String,
    val success: Boolean,
    val details: SyncErrorDetails? = null
)

@Serializable
internal data class SyncErrorDetails(
    val success: Boolean,
    val error: SyncErrorDetail
)

@Serializable
internal data class SyncErrorDetail(
    val code: String,
    val message: String
)

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

internal suspend fun HttpResponse.processError(logger: Logger, errorMessageExtractor: suspend () -> String? = { null }) {
    val errorBody = bodyAsText()
    logger.e { "HTTP error response: status=${status}, body=$errorBody" }

    val parsedMessage = try {
        errorMessageExtractor()
    } catch (e: Exception) {
        logger.w { "Failed to parse error response, using raw body: ${e.message}" }
        null
    }

    throw SyncNetworkException(status, errorBody, parsedMessage)
}
