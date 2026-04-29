@file:OptIn(ExperimentalTime::class)

package com.quran.shared.persistence.repository.recentpage.extension

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.model.DatabaseRecentPage
import com.quran.shared.persistence.model.RecentPage
import com.quran.shared.persistence.util.toPlatform
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal fun DatabaseRecentPage.toRecentPage(): RecentPage {
    return RecentPage(
        page = page.toInt(),
        chapterNumber = first_ayah_sura?.toInt() ?: 0,
        verseNumber = first_ayah_verse?.toInt() ?: 0,
        lastUpdated = Instant.fromEpochMilliseconds(modified_at).toPlatform(),
        localId = local_id.toString()
    )
}

internal fun DatabaseRecentPage.toRecentPageMutation(): LocalModelMutation<RecentPage> {
    val mutation = when {
        deleted == 1L -> Mutation.DELETED
        is_edited == 1L -> Mutation.MODIFIED
        else -> Mutation.CREATED
    }
    return LocalModelMutation(
        mutation = mutation,
        model = toRecentPage(),
        remoteID = remote_id,
        localID = local_id.toString()
    )
}
