package com.quran.shared.persistence.repository.bookmark

import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.DatabaseBookmark

/** Transaction-scoped access to persisted ayah bookmarks. */
internal class AyahBookmarkStore(
    private val database: QuranDatabase
) {
    fun resolve(
        sura: Int,
        ayah: Int,
        createdAt: Long,
        modifiedAt: Long,
        existing: DatabaseBookmark? = get(sura, ayah)
    ): AyahBookmarkResolution {
        if (existing?.deleted == 0L) {
            return AyahBookmarkResolution(existing, AyahBookmarkResolutionState.EXISTING)
        }

        database.bookmarksQueries.upsertAyahBookmark(
            remote_id = null,
            sura = sura.toLong(),
            ayah = ayah.toLong(),
            created_at = createdAt,
            modified_at = modifiedAt
        )
        val resolved = requireNotNull(get(sura, ayah)) {
            "Expected ayah bookmark for $sura:$ayah after upsert."
        }
        check(resolved.deleted == 0L) { "Expected active ayah bookmark for $sura:$ayah after upsert." }
        return AyahBookmarkResolution(
            bookmark = resolved,
            state = if (existing == null) {
                AyahBookmarkResolutionState.INSERTED
            } else {
                AyahBookmarkResolutionState.REACTIVATED
            }
        )
    }

    fun get(sura: Int, ayah: Int): DatabaseBookmark? =
        database.bookmarksQueries
            .getBookmarkForAyah(sura.toLong(), ayah.toLong())
            .executeAsOneOrNull()
}

internal data class AyahBookmarkResolution(
    val bookmark: DatabaseBookmark,
    val state: AyahBookmarkResolutionState
)

internal enum class AyahBookmarkResolutionState {
    EXISTING,
    INSERTED,
    REACTIVATED
}
