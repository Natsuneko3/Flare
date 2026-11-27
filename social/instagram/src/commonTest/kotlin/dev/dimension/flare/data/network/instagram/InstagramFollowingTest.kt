package dev.dimension.flare.data.network.instagram

import dev.dimension.flare.common.JSON
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InstagramFollowingTest {
    private fun frame(json: String): JsonObject = JSON.parseToJsonElement(json) as JsonObject

    @Test
    fun authenticatesTheSignedInViewerAndBuildsFollowingOnlyRequest() {
        val html = """
            <script>["PolarisViewer",[],{"data":{"id":"999"}}]</script>
            <script type="application/json">{"require":[["PolarisViewer",[],{"data":{"id":"123"}}],
            ["DTSGInitialData",[],{"token":"fb"}],["LSD",[],{"token":"lsd"}]]}</script>
        """.trimIndent()
        val data = InstagramBootstrap(html)
        data.requireViewer("123")
        assertFailsWith<InstagramAuthenticationRequired> { data.requireViewer("999") }
        assertFailsWith<InstagramAuthenticationRequired> { InstagramBootstrap("<html>Log in</html>").requireViewer("123") }
        val form = data.form("page2", null).formData
        assertEquals("lsd", form["lsd"])
        assertEquals("fb", form["fb_dtsg"])
        val variables = frame(form["variables"]!!)
        assertEquals("following", variables.string("variant"))
        assertEquals("page2", variables.string("after"))
    }

    @Test
    fun mergesDeferredEdgesAndPageInfoWithoutReturningSuggestions() {
        val initial = frame("""{"data":{"$FOLLOWING_FIELD":{"edges":[{"node":{"media":{"pk":"101"}}}],"page_info":{}}}}""")
        val addition = frame("""{"path":["$FOLLOWING_FIELD","edges",1],"items":[{"node":{"is_ad":true,"media":{"pk":"ad"}}},{"node":{"media":{"pk":"103"}}}]}""")
        val info = frame("""{"path":["$FOLLOWING_FIELD","page_info"],"data":{"has_next_page":true,"end_cursor":"page2"}}""")
        val result = followingPage(listOf(initial, addition, info))
        assertEquals(listOf("101", "103"), result.items.map { it.string("pk") })
        assertEquals("page2", result.nextCursor)
    }

    @Test
    fun handlesEmptyFollowingResultsAndRejectsOtherFeedsOrIncompleteFrames() {
        val empty = frame("""{"data":{"$FOLLOWING_FIELD":{"edges":[],"page_info":{"has_next_page":false,"end_cursor":"stale"}}}}""")
        val page = followingPage(listOf(empty))
        assertEquals(emptyList(), page.items)
        assertNull(page.nextCursor)
        assertFailsWith<InstagramRequestException> { followingPage(listOf(frame("""{"data":{"$FOLLOWING_FIELD":{"pagination_source":"home","edges":[],"page_info":{}}}}"""))) }
        assertFailsWith<InstagramRequestException> { followingPage(listOf(frame("""{"data":{}}"""))) }
    }
}
