package dev.dimension.flare.data.database.cache.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import dev.dimension.flare.data.database.cache.model.DbPluginPostWidget
import dev.dimension.flare.model.AccountType
import kotlin.native.HiddenFromObjC

private const val MAX_SQL_BIND_PARAMS = 500

@Dao
@HiddenFromObjC
public interface PluginPostWidgetDao {
    @Query("SELECT * FROM DbPluginPostWidget WHERE postEntityKey IN (:postEntityKeys)")
    public suspend fun getByPostKeysChunk(postEntityKeys: List<String>): List<DbPluginPostWidget>

    public suspend fun getByPostKeys(postEntityKeys: List<String>): List<DbPluginPostWidget> {
        if (postEntityKeys.isEmpty()) {
            return emptyList()
        }
        val distinctKeys = postEntityKeys.distinct()
        if (distinctKeys.size <= MAX_SQL_BIND_PARAMS) {
            return getByPostKeysChunk(distinctKeys)
        }
        val results = ArrayList<DbPluginPostWidget>(distinctKeys.size)
        distinctKeys.chunked(MAX_SQL_BIND_PARAMS).forEach { chunk ->
            results += getByPostKeysChunk(chunk)
        }
        return results
    }

    @Upsert
    public suspend fun upsertAll(items: List<DbPluginPostWidget>)

    @Query("DELETE FROM DbPluginPostWidget WHERE pluginId = :pluginId")
    public suspend fun deleteByPluginId(pluginId: String)

    @Query("DELETE FROM DbPluginPostWidget WHERE postEntityKey >= :prefix AND postEntityKey < :upperBound")
    public suspend fun deleteByPostKeyRange(
        prefix: String,
        upperBound: String,
    )

    public suspend fun deleteByAccountType(accountType: AccountType) {
        val prefix = "${accountType}_"
        val upperBound = "${accountType}\u0060"
        deleteByPostKeyRange(prefix = prefix, upperBound = upperBound)
    }

    @Query("DELETE FROM DbPluginPostWidget WHERE expiresAtMillis IS NOT NULL AND expiresAtMillis < :cutoffMillis")
    public suspend fun deleteExpiredBefore(cutoffMillis: Long)

    @Query(
        "DELETE FROM DbPluginPostWidget " +
            "WHERE rowid NOT IN (" +
            "SELECT rowid FROM DbPluginPostWidget " +
            "ORDER BY createdAtMillis DESC, postEntityKey DESC, pluginId DESC " +
            "LIMIT :maxRows" +
            ")",
    )
    public suspend fun trimOldestKeepingQuery(maxRows: Int)

    @Query("DELETE FROM DbPluginPostWidget")
    public suspend fun deleteAll()

    public suspend fun trimOldestKeeping(maxRows: Int) {
        if (maxRows <= 0) {
            deleteAll()
        } else {
            trimOldestKeepingQuery(maxRows)
        }
    }
}
