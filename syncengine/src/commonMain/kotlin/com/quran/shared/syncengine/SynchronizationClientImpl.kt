package com.quran.shared.syncengine

internal class SynchronizationClientImpl(
    val bookmarksConfigurations: PageBookmarksSynchronizationConfigurations,
    val authenticationDataFetcher: AuthenticationDataFetcher): SynchronizationClient {

    override fun localDataUpdated() {
        TODO("Not yet implemented")
    }

    override fun applicationStarted() {
        TODO("Not yet implemented")
    }
}