package com.quran.shared.persistence.model

import com.quran.shared.persistence.Bookmark
import com.quran.shared.persistence.Bookmark_collection
import com.quran.shared.persistence.Collection
import com.quran.shared.persistence.Note
import com.quran.shared.persistence.Reading_session
import com.quran.shared.persistence.Reading_bookmark
import com.quran.shared.persistence.GetUnsyncedReadingBookmarks
import com.quran.shared.persistence.GetUnsyncedBookmarkRows

internal typealias DatabaseBookmark = Bookmark
internal typealias DatabaseCollection = Collection
internal typealias DatabaseBookmarkCollection = Bookmark_collection
internal typealias DatabaseNote = Note
internal typealias DatabaseReadingSession = Reading_session
internal typealias DatabaseReadingBookmark = Reading_bookmark
internal typealias DatabaseUnsyncedReadingBookmark = GetUnsyncedReadingBookmarks
internal typealias DatabaseUnsyncedBookmark = GetUnsyncedBookmarkRows
