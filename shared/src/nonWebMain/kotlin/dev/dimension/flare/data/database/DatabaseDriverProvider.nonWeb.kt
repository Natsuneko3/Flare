package dev.dimension.flare.data.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public actual fun createDatabaseDriver(): SQLiteDriver = JournalSizeLimitedDriver(BundledSQLiteDriver())

internal actual val DEFAULT_READER_POOL_SIZE: Int = 4

private class JournalSizeLimitedDriver(
    private val delegate: SQLiteDriver,
) : SQLiteDriver by delegate {
    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        try {
            connection.execSQL("PRAGMA journal_size_limit = 0")
            return connection
        } catch (t: Throwable) {
            connection.close()
            throw t
        }
    }
}
