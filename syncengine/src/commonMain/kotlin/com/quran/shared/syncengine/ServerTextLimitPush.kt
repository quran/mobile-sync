package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.ServerTextLimit

/** Holds back creates and updates the server would reject for length, since one invalid row fails the whole push. */
internal class ServerTextLimitPush<Model>(
    mutationsToPush: List<LocalModelMutation<Model>>,
    limit: ServerTextLimit,
    resourceName: String,
    logger: Logger,
    text: (Model) -> String?
) {
    val pushable: List<LocalModelMutation<Model>>
    private val held: List<LocalModelMutation<Model>>

    init {
        val (accepted, rejected) = mutationsToPush.partition { mutation ->
            val value = text(mutation.model)
            mutation.mutation == Mutation.DELETED || value == null || limit.accepts(value)
        }
        rejected.forEach { mutation ->
            logger.w {
                "Leaving $resourceName mutation out of the push until it fits the server's length limits: " +
                    "localId=${mutation.localID}, mutation=${mutation.mutation}, " +
                    "length=${limit.serverLength(text(mutation.model).orEmpty())}, " +
                    "allowed=${limit.minLength}..${limit.maxLength}"
            }
        }
        pushable = accepted
        held = rejected
    }

    /** [localMutations] without the held ones, so they aren't cleared as if they had synced. */
    fun withoutHeld(localMutations: List<LocalModelMutation<Model>>): List<LocalModelMutation<Model>> =
        localMutations.filterNot { local -> held.any { it === local } }
}
