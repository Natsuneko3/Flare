package dev.dimension.flare.data.network.xqt.model

import dev.dimension.flare.common.decodeJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PinnedTimelinesManagementSheetResponseTest {
    @Test
    fun extractsOnlyValidTagPinnedTimelines() {
        val response =
            """
            {
              "data": {
                "pinnable_timelines": {
                  "pinnable_timelines": [
                    {
                      "__typename": "ListPinnedTimeline",
                      "list": {"rest_id": "list-id", "name": "A list"}
                    },
                    {
                      "__typename": "TagPinnedTimeline",
                      "icon_name": "topic_technology",
                      "name": "Technology",
                      "scribe": "scribe-id",
                      "tab_label": "Tech",
                      "tag": "tag-id"
                    },
                    {
                      "__typename": "CommunityPinnedTimeline",
                      "community_results": {"id": "community-id"}
                    },
                    {
                      "__typename": "TagPinnedTimeline",
                      "name": "Missing tag"
                    }
                  ]
                }
              }
            }
            """.trimIndent().decodeJson<PinnedTimelinesManagementSheetResponse>()

        assertEquals(
            listOf(
                TagPinnedTimeline(
                    iconName = "topic_technology",
                    name = "Technology",
                    scribe = "scribe-id",
                    tabLabel = "Tech",
                    tag = "tag-id",
                ),
            ),
            response.tagPinnedTimelines,
        )
        assertEquals("Tech", response.tagPinnedTimelines.single().title)
    }

    @Test
    fun filtersBlankEntriesAndKeepsTheFirstValidCategoryForEachTag() {
        val response =
            """
            {"data":{"pinnable_timelines":{"pinnable_timelines":[
              {"__typename":"TagPinnedTimeline","name":"Missing tag"},
              {"__typename":"TagPinnedTimeline","name":"Blank tag","tag":"   "},
              {"__typename":"TagPinnedTimeline","name":"  ","tab_label":" ","tag":"invalid-name"},
              {"__typename":"TagPinnedTimeline","name":"Business & Finance","tab_label":"Business","tag":"business"},
              {"__typename":"TagPinnedTimeline","name":"Duplicate business","tag":"business"},
              {"__typename":"TagPinnedTimeline","name":" ","tab_label":"J-Pop","tag":"music"},
              {"__typename":"TagPinnedTimeline","name":"Technology","tab_label":" ","tag":"technology"},
              {"name":"Unknown type","tag":"unknown"}
            ]}}}
            """.trimIndent().decodeJson<PinnedTimelinesManagementSheetResponse>()

        assertEquals(listOf("business", "music", "technology"), response.tagPinnedTimelines.map { it.tag })
        assertEquals(listOf("Business", "J-Pop", "Technology"), response.tagPinnedTimelines.map { it.title })
        assertEquals("Business & Finance", response.tagPinnedTimelines.first().name)
    }

    @Test
    fun distinguishesAnExplicitEmptyCatalogFromAMissingArray() {
        val empty =
            """{"data":{"pinnable_timelines":{"pinnable_timelines":[]}}}"""
                .decodeJson<PinnedTimelinesManagementSheetResponse>()
        val missing =
            """{"data":{"pinnable_timelines":{}}}"""
                .decodeJson<PinnedTimelinesManagementSheetResponse>()

        assertEquals(emptyList(), empty.data?.pinnableTimelines?.items)
        assertNull(missing.data?.pinnableTimelines?.items)
    }

    @Test
    fun retainsGraphqlErrorsWithoutCatalogData() {
        val response =
            """{"errors":[{"message":"Try again"}]}"""
                .decodeJson<PinnedTimelinesManagementSheetResponse>()

        assertNull(response.data)
        assertEquals("Try again", response.errors?.single()?.message)
    }
}
