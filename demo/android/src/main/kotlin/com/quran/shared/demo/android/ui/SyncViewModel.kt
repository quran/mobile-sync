package com.quran.shared.demo.android.ui

import androidx.lifecycle.ViewModel
import com.quran.shared.auth.service.AuthService
import com.quran.shared.pipeline.SyncService
import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.CollectionAyahBookmark
import com.quran.shared.persistence.model.CollectionWithAyahBookmarks
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.model.ReadingSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class SyncViewModel(
    private val authService: AuthService,
    private val service: SyncService
) : ViewModel() {

    val authState: StateFlow<com.quran.shared.auth.model.AuthState> = service.authState
    
    val bookmarks: Flow<List<AyahBookmark>> = service.bookmarks
    val readingBookmark: Flow<ReadingBookmark?> = service.readingBookmark
    
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

    suspend fun logout(clearLocalData: Boolean = false) {
        service.logout(clearLocalData)
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

    suspend fun addBookmark(sura: Int, ayah: Int): AyahBookmark {
        return service.addBookmark(sura, ayah)
    }

    suspend fun addAyahReadingBookmark(sura: Int, ayah: Int): AyahReadingBookmark {
        return service.addAyahReadingBookmark(sura, ayah)
    }

    suspend fun addPageReadingBookmark(page: Int): PageReadingBookmark {
        return service.addPageReadingBookmark(page)
    }

    suspend fun deleteReadingBookmark() {
        service.deleteReadingBookmark()
    }

    suspend fun deleteBookmark(bookmark: AyahBookmark) {
        service.deleteBookmark(bookmark)
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

    suspend fun removeBookmarkFromCollection(collectionId: String, bookmark: AyahBookmark) {
        service.removeBookmarkFromCollection(collectionId, bookmark)
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

    fun getBookmarksForCollectionFlow(collectionLocalId: String): Flow<List<com.quran.shared.persistence.model.CollectionAyahBookmark>> =
        service.getBookmarksForCollectionFlow(collectionLocalId)

}
