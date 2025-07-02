package com.quran.shared.persistence

import app.cash.sqldelight.db.SqlDriver

expect class TestDatabaseDriver() {
    fun createDriver(): SqlDriver
} 