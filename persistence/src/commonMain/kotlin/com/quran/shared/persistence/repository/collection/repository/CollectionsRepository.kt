package com.quran.shared.persistence.repository.collection.repository

import com.quran.shared.persistence.model.Collection

interface CollectionsRepository {
    /**
     * Fetch and returns all collections.
     *
     * @return List<Collection> the current list of collections
     */
    suspend fun getAllCollections(): List<Collection>

    /**
     * Add a collection with the provided name.
     */
    suspend fun addCollection(name: String): Collection

    /**
     * Update the name of a collection identified by its local ID.
     */
    suspend fun updateCollection(localId: String, name: String): Collection

    /**
     * Delete a collection identified by its local ID.
     */
    suspend fun deleteCollection(localId: String): Boolean
}
