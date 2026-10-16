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
 * The schema of [MobileSyncDatabase]. mobile-sync is pre-release, so the schema stays at version 1 and has no
 * migrations: a schema change edits the tables in place, and testers reinstall the app.
 */
@HiddenFromObjC
object MobileSyncSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

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
    ): QueryResult.Value<Unit> = QueryResult.Unit
}
