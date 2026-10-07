package com.quran.shared.persistence

/**
 * Thrown when SQLite reports the disk is full (SQLITE_FULL) while opening the database.
 *
 * A full disk never deletes the database. Opening again after storage is freed retries.
 *
 * A full disk can also surface as a different error, such as SQLITE_CANTOPEN, or a failed rollback
 * that hides SQLITE_FULL. Callers that need certainty should check free space on any open failure.
 *
 * @param cause The SQLite failure that reported the full disk.
 */
class DatabaseStorageFullException(
    cause: Throwable
) : Exception("Not enough storage to open the database", cause)
