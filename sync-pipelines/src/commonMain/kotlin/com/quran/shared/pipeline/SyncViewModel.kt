package com.quran.shared.pipeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quran.shared.auth.service.AuthService
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.CollectionWithBookmarks
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.util.QuranActionsUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class SyncViewModel(
    private val authService: AuthService,
    private val service: SyncService
) : ViewModel() {

    val authState = service.authState
    val bookmarks: Flow<List<Bookmark>> = service.bookmarks
    val collectionsWithBookmarks: Flow<List<CollectionWithBookmarks>> = service.collectionsWithBookmarks
    val notes: Flow<List<Note>> = service.notes

    fun login() {
        viewModelScope.launch {
            try {
                authService.login()
            } catch (e: Exception) {
                // Error handled by service state
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                authService.logout()
            } catch (e: Exception) {
                // Error handled by service state
            }
        }
    }

    fun clearError() {
        authService.clearError()
    }

    fun triggerSync() {
        service.triggerSync()
    }

    fun addBookmark(page: Int) {
        viewModelScope.launch {
            try {
                service.addBookmark(page)
            } catch (e: Exception) {
                // Error handled by service logging
            }
        }
    }

    fun addBookmark(sura: Int, ayah: Int) {
        viewModelScope.launch {
            try {
                service.addBookmark(sura, ayah)
            } catch (e: Exception) {
                // Error handled by service logging
            }
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            try {
                service.deleteBookmark(bookmark)
            } catch (e: Exception) {
                // Error handled by service logging
            }
        }
    }

    fun addCollection(name: String) {
        viewModelScope.launch {
            try {
                service.addCollection(name)
            } catch (e: Exception) {
            }
        }
    }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch {
            try {
                service.deleteCollection(collectionId)
            } catch (e: Exception) {
            }
        }
    }

    fun addRandomBookmarkToCollection(collectionId: String) {
        viewModelScope.launch {
            try {
                val quranUtils = QuranActionsUtils
                val sura = quranUtils.getRandomSura()
                val ayah = quranUtils.getRandomAyah(sura)
                
                // Add the ayah bookmark and get the created bookmark
                val bookmark = service.addBookmark(sura, ayah)
                
                // Add the newly created bookmark to the collection
                service.addBookmarkToCollection(collectionId, bookmark)
            } catch (e: Exception) {
                // Error logging would be handled in service or here
            }
        }
    }

    fun removeBookmarkFromCollection(collectionId: String, bookmark: Bookmark) {
        viewModelScope.launch {
            try {
                service.removeBookmarkFromCollection(collectionId, bookmark)
            } catch (e: Exception) {
            }
        }
    }

    fun addNote(body: String, startAyahId: Long, endAyahId: Long) {
        viewModelScope.launch {
            try {
                service.addNote(body, startAyahId, endAyahId)
            } catch (e: Exception) {
            }
        }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch {
            try {
                service.deleteNote(noteId)
            } catch (e: Exception) {
            }
        }
    }
}
