package com.quran.shared.persistence.repository.readingsession.repository

import com.quran.shared.persistence.model.ReadingSession
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
     * Add a reading session to the list.
     *
     * @param chapterNumber the sura number of the session
     * @param verseNumber the verse number of the session
     * @return the [ReadingSession]
     */
    @NativeCoroutines
    suspend fun addReadingSession(chapterNumber: Int, verseNumber: Int): ReadingSession

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
    suspend fun deleteReadingSession(chapterNumber: Int, verseNumber: Int): Boolean
}
