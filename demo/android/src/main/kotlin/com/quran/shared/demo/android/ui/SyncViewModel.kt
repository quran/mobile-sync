package com.quran.shared.demo.android.ui

import androidx.lifecycle.ViewModel
import com.quran.shared.pipeline.SyncAuthService
import com.quran.shared.pipeline.QuranDataService
import com.quran.shared.persistence.model.CollectionAyahBookmark
import com.quran.shared.persistence.model.CollectionWithAyahBookmarks
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.model.ReadingSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class SyncViewModel(
    private val authService: SyncAuthService,
    private val service: QuranDataService
) : ViewModel() {

    val authState: StateFlow<com.quran.shared.auth.model.AuthState> = service.authState
    val isAuthenticationConfigured: Boolean = authService.isAuthenticationConfigured
    
    val readingBookmarks: Flow<List<ReadingBookmark>> = service.readingBookmarks
    
    val collectionsWithBookmarks: Flow<List<CollectionWithAyahBookmarks>> =
        service.collectionsWithBookmarks
    
    val notes: Flow<List<Note>> = service.notes
    val readingSessions: Flow<List<ReadingSession>> = service.readingSessions

    suspend fun login() {
        authService.login()
    }

    suspend fun loginWithReauthentication() {
        authService.loginWithReauthentication()
    }

    suspend fun logout(clearLocalData: Boolean = true) {
        authService.logout(clearLocalData)
    }

    suspend fun addReadingSession(sura: Int, ayah: Int): ReadingSession {
        return service.addReadingSession(sura, ayah)
    }

    fun clearError() {
        authService.clearError()
    }

    fun triggerSync() {
        service.triggerSync()
    }

    suspend fun setAyahReadingBookmark(slot: ReadingBookmarkSlot, sura: Int, ayah: Int): ReadingBookmark {
        return service.setAyahReadingBookmark(slot, sura, ayah)
    }

    suspend fun setPageReadingBookmark(slot: ReadingBookmarkSlot, page: Int): ReadingBookmark {
        return service.setPageReadingBookmark(slot, page)
    }

    suspend fun clearReadingBookmark(slot: ReadingBookmarkSlot) {
        service.clearReadingBookmark(slot)
    }

    suspend fun addCollection(name: String) {
        service.addCollection(name)
    }

    suspend fun deleteCollection(collectionId: String) {
        service.deleteCollection(collectionId)
    }

    suspend fun addAyahBookmarkToCollection(collectionId: String, sura: Int, ayah: Int) {
        service.addAyahBookmarkToCollection(collectionId, sura, ayah)
    }

    suspend fun removeAyahBookmarkFromCollection(collectionId: String, bookmark: CollectionAyahBookmark) {
        service.removeAyahBookmarkFromCollection(bookmark)
    }

    suspend fun addNote(body: String, startSura: Int, startAyah: Int, endSura: Int, endAyah: Int) {
        service.addNote(body, startSura, startAyah, endSura, endAyah)
    }

    suspend fun deleteNote(noteId: String) {
        service.deleteNote(noteId)
    }

}
