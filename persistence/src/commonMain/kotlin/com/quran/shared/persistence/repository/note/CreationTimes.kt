package com.quran.shared.persistence.repository.note

/**
 * Note creation times for a batch of new notes, by [NoteStore.insert]'s rule: the first millisecond at or after the
 * asked one that no note has. Each run of taken milliseconds is walked once and then skipped, so a batch of n notes
 * costs about O(n) however their times cluster, where asking the database for each one rescans the run every time.
 * [taken] holds every note's creation time, and nothing else adds notes while the batch is in use.
 */
internal class CreationTimes(taken: Collection<Long>) {
    private val taken = taken.toHashSet()

    /** For a taken millisecond, a later one where the search can go on: every millisecond between them is taken. */
    private val skipTo = HashMap<Long, Long>()

    /** The first free millisecond at or after [at], now taken. */
    fun take(at: Long): Long {
        val walked = mutableListOf<Long>()
        var candidate = at
        while (candidate in taken) {
            walked += candidate
            candidate = skipTo[candidate] ?: (candidate + 1)
        }
        taken += candidate
        walked.forEach { skipTo[it] = candidate + 1 }
        return candidate
    }
}
