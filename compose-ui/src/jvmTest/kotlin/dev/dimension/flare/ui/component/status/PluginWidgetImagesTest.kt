package dev.dimension.flare.ui.component.status

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import dev.dimension.flare.ui.component.LocalNetworkImageAllowHardware
import dev.dimension.flare.ui.model.UiPluginWidgetCard
import dev.dimension.flare.ui.model.UiWidgetNode
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
class PluginWidgetImagesTest {
    private val requests = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        SingletonImageLoader.setUnsafe { context ->
            ImageLoader
                .Builder(context)
                .components {
                    add(
                        Interceptor { chain ->
                            requests += chain.request.data.toString()
                            SuccessResult(ColorImage(0xffff0000.toInt(), 10, 10), chain.request, DataSource.MEMORY)
                        },
                    )
                }.build()
        }
    }

    @After
    fun tearDown() {
        SingletonImageLoader.reset()
    }

    @Test
    fun cappedSquareImagePaintsOnlyBetweenItsTitleAndFollowingText() =
        runComposeUiTest {
            val card =
                UiPluginWidgetCard(
                    pluginId = "example",
                    pluginName = "Example",
                    pluginIconPath = null,
                    id = "square",
                    title = "Title",
                    body = persistentListOf(UiWidgetNode.Image("https://example.com/image", "Square", 1f), UiWidgetNode.Text("Below")),
                )
            setContent {
                CompositionLocalProvider(LocalNetworkImageAllowHardware provides false) {
                    PluginWidgetStackComponent(persistentListOf(card), Modifier.width(380.dp))
                }
            }
            val title = onNodeWithText("Title").fetchSemanticsNode().boundsInRoot
            val image = onNodeWithContentDescription("Square").fetchSemanticsNode().boundsInRoot
            val below = onNodeWithText("Below").fetchSemanticsNode().boundsInRoot
            val root = onRoot()
            waitUntil(timeoutMillis = 5_000) {
                root.captureToImage().toPixelMap()[image.center.x.toInt(), image.center.y.toInt()] == Color.Red
            }
            assertTrue(image.top >= title.bottom && image.bottom <= below.top, "title=$title image=$image below=$below")
            assertEquals(200.dp, with(density) { image.height.toDp() })
            assertEquals(356.dp, with(density) { image.width.toDp() })
            val pixels = root.captureToImage().toPixelMap()
            assertNotEquals(Color.Red, pixels[title.center.x.toInt(), title.center.y.toInt()])
            assertNotEquals(Color.Red, pixels[below.center.x.toInt(), below.center.y.toInt()])
        }

    @Test
    fun premeasuringCardsDoesNotLoadTheirHeaderBodyOrMatchupImages() =
        runComposeUiTest {
            fun url(
                page: Int,
                image: String,
            ) = "https://example.com/$page/$image"

            val cards =
                (1..8)
                    .map { page ->
                        UiPluginWidgetCard(
                            pluginId = "example",
                            pluginName = "Example",
                            pluginIconPath = url(page, "icon"),
                            id = page.toString(),
                            title = "Page $page",
                            body =
                                persistentListOf(
                                    UiWidgetNode.Image(url(page, "image"), "Image"),
                                    UiWidgetNode.Matchup(
                                        left = UiWidgetNode.MatchupSide("Left", iconUrl = url(page, "left")),
                                        right = UiWidgetNode.MatchupSide("Right", iconUrl = url(page, "right")),
                                    ),
                                ),
                        )
                    }.toImmutableList()
            setContent { PluginWidgetStackComponent(cards, Modifier.width(380.dp)) }

            val images = listOf("icon", "image", "left", "right")
            val first = images.map { url(1, it) }
            waitUntil(timeoutMillis = 5_000) { requests.containsAll(first) }
            assertEquals(first.sorted(), requests.sorted())
            onNodeWithTag("plugin_widget_pager").performTouchInput { swipeLeft() }
            val second = images.map { url(2, it) }
            waitUntil(timeoutMillis = 5_000) { requests.containsAll(second) }
            assertTrue(requests.none { it.startsWith("https://example.com/8/") })
        }
}
