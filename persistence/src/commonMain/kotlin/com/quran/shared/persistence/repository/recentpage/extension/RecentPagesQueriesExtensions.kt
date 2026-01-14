@file:OptIn(ExperimentalTime::class)

package com.quran.shared.persistence.repository.recentpage.extension

import com.quran.shared.persistence.model.DatabaseRecentPage
import com.quran.shared.persistence.model.RecentPage
import com.quran.shared.persistence.util.toPlatform
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal fun DatabaseRecentPage.toRecentPage(): RecentPage {
    return RecentPage(
        page = page.toInt(),
        lastUpdated = Instant.fromEpochMilliseconds(modified_at).toPlatform(),
        localId = local_id.toString()
    )
}
