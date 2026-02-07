package com.quran.shared.pipeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quran.shared.auth.service.AuthService
import com.quran.shared.persistence.model.Bookmark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class SyncViewModel(
    private val authService: AuthService,
    private val service: SyncService
) : ViewModel() {

    val authState = service.authState
    val bookmarks: Flow<List<Bookmark>> = service.bookmarks

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
}
