package dev.dimension.flare.data.database.cache.model

import androidx.room3.Entity
import androidx.room3.Index
import kotlin.native.HiddenFromObjC

@Entity(
    tableName = "DbPluginPostWidget",
    primaryKeys = ["postEntityKey", "pluginId"],
    indices = [
        Index(value = ["pluginId"]),
        Index(value = ["expiresAtMillis"]),
    ],
)
@HiddenFromObjC
public data class DbPluginPostWidget(
    public val postEntityKey: String,
    public val pluginId: String,
    public val pluginCacheKey: String,
    public val sourceHash: String,
    public val cardsJson: String,
    public val createdAtMillis: Long,
    public val expiresAtMillis: Long?,
)
