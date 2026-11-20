package dev.dimension.flare.data.database.cache

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step

internal object TimelineRevisionCallback : RoomDatabase.Callback() {
    override suspend fun onOpen(connection: SQLiteConnection) {
        if (triggersUpToDate(connection)) {
            return
        }
        TRIGGERS.forEach { (oldName, sql) ->
            connection.executeSQL("DROP TRIGGER IF EXISTS $oldName")
            connection.executeSQL(sql)
        }
    }

    private suspend fun triggersUpToDate(connection: SQLiteConnection): Boolean =
        connection.prepare(CHECK_TRIGGERS_SQL).use { statement ->
            statement.step() && statement.getLong(0) == 1L
        }

    private val TRIGGERS =
        listOf(
            contentTrigger("timeline_status_insert_revision", "INSERT", "DbStatus", "NEW.id"),
            contentTrigger("timeline_status_delete_revision", "DELETE", "DbStatus", "OLD.id"),
            contentTrigger(
                name = "timeline_status_update_revision",
                event = "UPDATE OF contentFingerprint, renderHash",
                table = "DbStatus",
                contentId = "NEW.id",
                condition =
                    "WHEN OLD.contentFingerprint IS NOT NEW.contentFingerprint " +
                        "OR OLD.renderHash IS NOT NEW.renderHash",
            ),
            contentTrigger(
                "timeline_translation_insert_revision",
                "INSERT",
                "DbTranslation",
                "NEW.entityKey",
                "WHEN NEW.entityType = 'Status'",
            ),
            contentTrigger(
                "timeline_translation_delete_revision",
                "DELETE",
                "DbTranslation",
                "OLD.entityKey",
                "WHEN OLD.entityType = 'Status'",
            ),
            contentTrigger(
                name = "timeline_translation_update_revision",
                event = "UPDATE",
                table = "DbTranslation",
                contentId = "NEW.entityKey",
                condition =
                    "WHEN NEW.entityType = 'Status' AND (OLD.status IS NOT NEW.status " +
                        "OR OLD.displayMode IS NOT NEW.displayMode " +
                        "OR OLD.payload IS NOT NEW.payload " +
                        "OR OLD.statusReason IS NOT NEW.statusReason " +
                        "OR OLD.sourceHash IS NOT NEW.sourceHash " +
                        "OR OLD.targetLanguage IS NOT NEW.targetLanguage)",
            ),
            referenceTrigger(
                "timeline_semantic_reference_insert_revision",
                "INSERT",
                "status_reference",
                statusAndParentTimelines("NEW.statusId"),
            ),
            referenceTrigger(
                "timeline_semantic_reference_delete_revision",
                "DELETE",
                "status_reference",
                statusAndParentTimelines("OLD.statusId"),
            ),
            referenceTrigger(
                "timeline_semantic_reference_update_revision",
                "UPDATE",
                "status_reference",
                "(${statusAndParentTimelines("OLD.statusId")}) OR (${statusAndParentTimelines("NEW.statusId")})",
            ),
            referenceTrigger(
                "timeline_presentation_reference_delete_revision",
                "DELETE",
                "timeline_item_presentation_reference",
                "pagingKey = OLD.pagingKey AND _id = OLD.timelineId",
            ),
            referenceTrigger(
                "timeline_presentation_reference_update_revision",
                "UPDATE",
                "timeline_item_presentation_reference",
                "(pagingKey = OLD.pagingKey AND _id = OLD.timelineId) " +
                    "OR (pagingKey = NEW.pagingKey AND _id = NEW.timelineId)",
            ),
        )

    // Trigger existence is checked by versioned name (*_v2) to avoid running 22 DDL statements on every open.
    // When modifying any trigger definition in TRIGGERS, bump the version suffix (e.g. _v3) and include the
    // previous versioned names in the legacy drop list.
    private val CHECK_TRIGGERS_SQL =
        buildString {
            val currentNames = TRIGGERS.joinToString(", ") { (name, _) -> "'${name}_v2'" }
            val legacyNames = TRIGGERS.joinToString(", ") { (name, _) -> "'$name'" }
            append("SELECT ")
            append("(SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name IN (")
            append(currentNames)
            append(")) = ")
            append(TRIGGERS.size)
            append(" AND NOT EXISTS (SELECT 1 FROM sqlite_master WHERE type = 'trigger' AND name IN (")
            append(legacyNames)
            append("))")
        }

    private fun contentTrigger(
        name: String,
        event: String,
        table: String,
        contentId: String,
        condition: String = "",
    ): Pair<String, String> =
        name to
            """
            CREATE TRIGGER IF NOT EXISTS ${name}_v2
            AFTER $event ON $table
            $condition
            BEGIN
                ${contentRevisionUpdate(contentId)}
            END
            """.trimIndent()

    private fun contentRevisionUpdate(contentId: String): String =
        """
        UPDATE DbPagingTimeline
        SET contentRevision = contentRevision + 1
        WHERE _id IN (
            SELECT timeline._id
            FROM DbPagingTimeline AS timeline
            WHERE timeline.statusId = $contentId
            UNION
            SELECT timeline._id
            FROM DbPagingTimeline AS timeline
            INNER JOIN status_reference AS semantic
                ON semantic.statusId = timeline.statusId
            WHERE semantic.referenceStatusId = $contentId
            UNION
            SELECT timeline._id
            FROM DbPagingTimeline AS timeline
            INNER JOIN timeline_item_presentation_reference AS presentation
                ON presentation.pagingKey = timeline.pagingKey
                AND presentation.timelineId = timeline._id
            WHERE presentation.referenceStatusId = $contentId
            UNION
            SELECT timeline._id
            FROM DbPagingTimeline AS timeline
            INNER JOIN timeline_item_presentation_reference AS parent
                ON parent.pagingKey = timeline.pagingKey
                AND parent.timelineId = timeline._id
                AND parent.presentationType = 'InlineParent'
            INNER JOIN status_reference AS nested
                ON nested.statusId = parent.referenceStatusId
            WHERE nested.referenceStatusId = $contentId
        );
        """.trimIndent()

    private fun referenceTrigger(
        name: String,
        event: String,
        table: String,
        condition: String,
    ): Pair<String, String> =
        name to
            """
            CREATE TRIGGER IF NOT EXISTS ${name}_v2
            AFTER $event ON $table
            BEGIN
                UPDATE DbPagingTimeline
                SET contentRevision = contentRevision + 1
                WHERE $condition;
            END
            """.trimIndent()

    private fun statusAndParentTimelines(statusId: String): String =
        """
        statusId = $statusId OR _id IN (
            SELECT timeline._id
            FROM DbPagingTimeline AS timeline
            INNER JOIN timeline_item_presentation_reference AS parent
                ON parent.pagingKey = timeline.pagingKey
                AND parent.timelineId = timeline._id
                AND parent.presentationType = 'InlineParent'
            WHERE parent.referenceStatusId = $statusId
        )
        """.trimIndent()
}
