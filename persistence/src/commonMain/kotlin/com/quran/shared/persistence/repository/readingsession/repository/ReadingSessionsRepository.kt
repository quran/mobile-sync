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
     * Update an existing reading session by local ID.
     *
     * @param localId the local ID of the session to update
     * @param sura the new sura number of the session
     * @param ayah the new ayah number of the session
     * @return the updated [ReadingSession]
     */
    @NativeCoroutines
    suspend fun updateReadingSession(localId: String, sura: Int, ayah: Int): ReadingSession

    @NativeCoroutines
    suspend fun updateReadingSession(
        localId: String,
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
     * @return a boolean denoting success
     */
    @NativeCoroutines
    suspend fun deleteReadingSession(sura: Int, ayah: Int): Boolean
}
