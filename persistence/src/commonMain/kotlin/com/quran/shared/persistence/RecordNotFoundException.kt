package com.quran.shared.persistence

enum class RecordType {
    NOTE,
    COLLECTION,
    READING_SESSION
}

/**
 * Thrown when a write names a record by an [id] that identifies no active record of its [type]. With several devices
 * this is expected: a note deleted on the web while it is being edited here is gone once a sync lands in between.
 */
class RecordNotFoundException(val type: RecordType, val id: String) :
    IllegalArgumentException("${type.name} id=$id not found.")
