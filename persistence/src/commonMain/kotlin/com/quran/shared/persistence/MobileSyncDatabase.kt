package com.quran.shared.persistence

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.types.CollectionsRepository
import kotlin.native.HiddenFromObjC

/**
 * The app's database. One SQLite file and driver hold two schemas, so one transaction can span both:
 * - [quran]: app metadata (the key-value store and the import history);
 * - [synced]: the user's data in plain domain tables, with its sync state (`sync_item`, the token, the account and the
 *   outbox), as the sync engine reads and writes it.
 */
@HiddenFromObjC
class MobileSyncDatabase(val driver: SqlDriver) {
    val quran: QuranDatabase = QuranDatabase(driver)
    val synced: SyncV2Database = SyncV2Database(driver)
}

/**
 * The schema of [MobileSyncDatabase]. mobile-sync is pre-release with no backward compatibility, so a database from an
 * earlier schema version is dropped and created again (its user data is lost). Version 1 was the schema of the old
 * sync engine (pending columns on every domain table); version 2 is the plain tables of sync-v2; version 3 adds the
 * sync history (`sync_run`).
 */
@HiddenFromObjC
object MobileSyncSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 3

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        QuranDatabase.Schema.create(driver)
        SyncV2Database.Schema.create(driver)
        CollectionsRepository.ensureFavorites(SyncV2Database(driver))
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion
    ): QueryResult.Value<Unit> {
        dropEverything(driver)
        return create(driver)
    }

    /** Drops every table; tables that reference others go first, so foreign keys never block a drop. */
    private fun dropEverything(driver: SqlDriver) {
        val tables = driver.executeQuery(
            identifier = null,
            sql = "SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            mapper = { cursor ->
                val rows = mutableListOf<Pair<String, String>>()
                while (cursor.next().value) rows += cursor.getString(0)!! to cursor.getString(1).orEmpty()
                QueryResult.Value(rows)
            },
            parameters = 0
        ).value
        tables.sortedByDescending { (_, sql) -> "REFERENCES" in sql.uppercase() }
            .forEach { (name, _) -> driver.execute(null, "DROP TABLE IF EXISTS \"$name\"", 0) }
    }
}
