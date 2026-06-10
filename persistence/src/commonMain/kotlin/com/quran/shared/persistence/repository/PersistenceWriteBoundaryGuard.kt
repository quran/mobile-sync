package com.quran.shared.persistence.repository

fun interface PersistenceWriteBoundaryGuard {
    suspend fun checkWriteBoundary()

    companion object {
        val Allow = PersistenceWriteBoundaryGuard {}
    }
}
