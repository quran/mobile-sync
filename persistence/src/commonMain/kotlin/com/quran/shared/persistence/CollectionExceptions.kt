package com.quran.shared.persistence

/**
 * Thrown when renaming a collection to a name another collection has. Names compare as the server compares them:
 * trimmed and case-insensitive.
 */
class DuplicateCollectionNameException(val name: String) :
    IllegalArgumentException("A collection named \"$name\" already exists.")

/**
 * Thrown when renaming or deleting a collection users cannot change: Favorites, the highlight collections, and the
 * color collections older Quran.com apps created, which the server protects.
 */
class ReadOnlyCollectionException(val id: String) : IllegalArgumentException("Collection $id is read-only.")
