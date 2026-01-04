package com.quran.shared.persistence.input

sealed class BookmarkMigration {
    data class Page(val page: Int) : BookmarkMigration()
    data class Ayah(val sura: Int, val ayah: Int) : BookmarkMigration()
}
