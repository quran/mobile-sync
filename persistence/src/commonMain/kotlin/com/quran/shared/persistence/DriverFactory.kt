package com.quran.shared.persistence

import app.cash.sqldelight.db.SqlDriver

expect class DriverFactory {
    fun makeDriver(): SqlDriver
}

fun makeDatabase(driverFactory: DriverFactory): QuranDatabase {
    val driver = driverFactory.makeDriver()
    return QuranDatabase(driver)
}