package com.quran.shared.persistence.repository.readingsession.repository

import com.quran.shared.persistence.model.ReadingSession
import com.quran.shared.persistence.util.PlatformDateTime
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import kotlinx.coroutines.flow.Flow

interface ReadingSessionsRepository {
    /**
     * Fetch and returns all reading sessions.
     *
     * @return List<ReadingSession> the current list of reading sessions
     */
    @NativeCoroutines
    suspend fun getReadingSessions(): List<ReadingSession>

    /**
     * Add a reading session to the list. Mobile-sync keeps the active reading-session list
     * bounded to its supported recent-session count.
     *
     * @param sura the sura number of the session
     * @param ayah the ayah number of the session
     * @return the [ReadingSession]
     */
    @NativeCoroutines
    suspend fun addReadingSession(sura: Int, ayah: Int): ReadingSession

    @NativeCoroutines
    suspend fun addReadingSession(sura: Int, ayah: Int, timestamp: PlatformDateTime): ReadingSession

    /**
     * Update an existing active reading session by its mobile-sync ID. If another active
     * session already occupies the destination, the source is deleted and the destination
     * is updated instead.
     *
     * @param id the mobile-sync ID of the session to update
     * @param sura the new sura number of the session
     * @param ayah the new ayah number of the session
     * @return the updated [ReadingSession], whose ID may be the existing destination's ID
     */
    @NativeCoroutines
    suspend fun updateReadingSession(id: String, sura: Int, ayah: Int): ReadingSession

    @NativeCoroutines
    suspend fun updateReadingSession(
        id: String,
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): ReadingSession

    /**
     * Returns a flow of all reading sessions for observation.
     */
    @NativeCoroutines
    fun getReadingSessionsFlow(): Flow<List<ReadingSession>>

    /**
     * Delete a reading session from the list.
     *
     * @return `true` when an active reading session was deleted, or `false` when no matching active session existed.
     */
    @NativeCoroutines
    suspend fun deleteReadingSession(sura: Int, ayah: Int): Boolean
}
