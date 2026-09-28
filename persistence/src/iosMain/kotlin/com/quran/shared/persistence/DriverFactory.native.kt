package com.quran.shared.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.kermit.Logger
import co.touchlab.sqliter.DatabaseFileContext
import co.touchlab.sqliter.interop.SQLiteExceptionErrorCode
import co.touchlab.sqliter.interop.SqliteErrorType

actual class DriverFactory internal constructor(
    private val name: String
) {
    constructor() : this(DATABASE_NAME)

    /**
     * Opens the database, replacing it with an empty one when SQLite reports it corrupt.
     *
     * Android's default open helper deletes corrupt databases. The native driver has no such
     * handler, so a corrupt file would otherwise fail every launch. Synced data is restored by the
     * next full sync; unsynced local changes in the corrupt file are lost.
     *
     * Only SQLITE_CORRUPT and SQLITE_NOTADB trigger deletion. SQLite reports them only after a read
     * succeeded and returned bad bytes. A file locked by data protection, for example before first
     * unlock, fails to open or read instead (SQLITE_CANTOPEN or SQLITE_IOERR), so it is kept.
     */
    actual fun makeDriver(): SqlDriver {
        return try {
            openCheckedDriver()
        } catch (e: Exception) {
            if (!e.isCorruption()) throw e
            logger.e(e) { "Database $name is corrupt; deleting and recreating it" }
            DatabaseFileContext.deleteDatabase(name)
            openCheckedDriver()
        }
    }

    private fun openCheckedDriver(): SqlDriver {
        val driver = NativeSqliteDriver(QuranDatabase.Schema, name)
        try {
            // Opening is lazy; the first statement opens the file and runs schema creation.
            val problems = driver.executeQuery(
                identifier = null,
                sql = "PRAGMA quick_check",
                mapper = { cursor ->
                    val rows = mutableListOf<String>()
                    while (cursor.next().value) {
                        rows += cursor.getString(0).orEmpty()
                    }
                    QueryResult.Value(rows)
                },
                parameters = 0
            ).value.filterNot { it == QUICK_CHECK_OK }
            if (problems.isNotEmpty()) {
                throw DatabaseCorruptException(problems.joinToString("\n"))
            }
        } catch (e: Exception) {
            driver.close()
            throw e
        }
        return driver
    }

    private companion object {
        const val DATABASE_NAME = "quran.db"
        const val QUICK_CHECK_OK = "ok"
        val logger = Logger.withTag("DriverFactory")
    }
}

private class DatabaseCorruptException(message: String) : IllegalStateException(message)

private fun Throwable.isCorruption(): Boolean =
    generateSequence(this) { it.cause }.any { error ->
        error is DatabaseCorruptException ||
            (error as? SQLiteExceptionErrorCode)?.errorType in corruptionErrorTypes
    }

private val corruptionErrorTypes = setOf(SqliteErrorType.SQLITE_CORRUPT, SqliteErrorType.SQLITE_NOTADB)
