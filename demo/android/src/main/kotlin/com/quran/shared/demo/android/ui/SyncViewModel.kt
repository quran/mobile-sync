package com.quran.shared.demo.android.ui

import androidx.lifecycle.ViewModel
import com.quran.shared.auth.service.AuthService
import com.quran.shared.pipeline.SyncService
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.CollectionWithBookmarks
import com.quran.shared.persistence.model.Note
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class SyncViewModel(
    private val authService: AuthService,
    private val service: SyncService
) : ViewModel() {

    val authState: StateFlow<com.quran.shared.auth.model.AuthState> = service.authState
    
    val bookmarks: Flow<List<Bookmark>> = service.bookmarks
    
    val collectionsWithBookmarks: Flow<List<CollectionWithBookmarks>> =
        service.collectionsWithBookmarks
    
    val notes: Flow<List<Note>> = service.notes

    suspend fun login() {
        authService.login()
    }

    suspend fun logout() {
        authService.logout()
    }

    fun clearError() {
        authService.clearError()
    }

    fun triggerSync() {
        service.triggerSync()
    }

    suspend fun addBookmark(page: Int): Bookmark {
        return service.addBookmark(page)
    }

    suspend fun addBookmark(sura: Int, ayah: Int): Bookmark {
        return service.addBookmark(sura, ayah)
    }

    suspend fun deleteBookmark(bookmark: Bookmark) {
        service.deleteBookmark(bookmark)
    }

    suspend fun addCollection(name: String) {
        service.addCollection(name)
    }

    suspend fun deleteCollection(collectionId: String) {
        service.deleteCollection(collectionId)
    }

    suspend fun addAyahBookmarkToCollection(collectionId: String, sura: Int, ayah: Int) {
        val bookmark = service.addBookmark(sura, ayah)
        service.addBookmarkToCollection(collectionId, bookmark)
    }

    suspend fun removeBookmarkFromCollection(collectionId: String, bookmark: Bookmark) {
        service.removeBookmarkFromCollection(collectionId, bookmark)
    }

    suspend fun addNote(body: String, startAyahId: Long, endAyahId: Long) {
        service.addNote(body, startAyahId, endAyahId)
    }

    suspend fun deleteNote(noteId: String) {
        service.deleteNote(noteId)
    }

    fun getBookmarksForCollectionFlow(collectionLocalId: String): Flow<List<com.quran.shared.persistence.model.CollectionBookmark>> =
        service.getBookmarksForCollectionFlow(collectionLocalId)

    override fun onCleared() {
        super.onCleared()
        service.clear()
    }
}
