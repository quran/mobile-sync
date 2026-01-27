package com.quran.shared.pipeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quran.shared.auth.ui.AuthViewModel
import com.quran.shared.persistence.model.Bookmark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * ViewModel wrapping the [MainSyncService].
 */
class SyncViewModel(
    val authViewModel: AuthViewModel,
    val service: MainSyncService
) : ViewModel() {

    val authState = service.authState
    val bookmarks: Flow<List<Bookmark>> = service.bookmarks

    fun triggerSync() {
        service.triggerSync()
    }

    /**
     * Adds a bookmark and triggers synchronization.
     * Launches in [viewModelScope] for Android.
     */
    fun addBookmark(page: Int) {
        viewModelScope.launch {
            try {
                service.addBookmark(page)
            } catch (e: Exception) {
                // Error handled by service logging
            }
        }
    }
}
