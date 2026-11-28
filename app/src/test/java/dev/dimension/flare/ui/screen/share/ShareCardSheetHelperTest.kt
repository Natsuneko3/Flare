package dev.dimension.flare.ui.screen.share

import android.app.Application
import android.content.Intent
import android.net.Uri
import dev.dimension.flare.common.MediaFileNamePolicy
import dev.dimension.flare.ui.screen.compose.ShortcutComposeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class ShareCardSheetHelperTest {
    @Test
    fun `crossPostInitialText formats valid url and stays empty for null blank or invalid urls`() {
        assertEquals("\n\nhttps://example.org/@alice", crossPostInitialText("https://example.org/@alice"))
        assertEquals("\n\nhttps://example.org/@alice", crossPostInitialText("  https://example.org/@alice  "))
        assertEquals("", crossPostInitialText(null))
        assertEquals("", crossPostInitialText(""))
        assertEquals("", crossPostInitialText("   "))
        assertEquals("", crossPostInitialText("/users/alice"))
        assertEquals("", crossPostInitialText("//example.org/@alice"))
        assertEquals("", crossPostInitialText("example.org/@alice"))
        assertEquals("", crossPostInitialText("javascript:alert(1)"))
    }

    @Test
    fun `saved screenshot file name uses provided prefix sanitizes key and appends timestamp`() {
        val before = System.currentTimeMillis()
        val profileFileName = MediaFileNamePolicy.screenshotFileName(key = "alice/example.org", prefix = "profile")
        val statusFileName = MediaFileNamePolicy.screenshotFileName(key = "123/example.org", prefix = "status")
        val after = System.currentTimeMillis()

        assertTimestampedPngFileName("profile_alice_example.org_", profileFileName, before, after)
        assertTimestampedPngFileName("status_123_example.org_", statusFileName, before, after)
    }

    private fun assertTimestampedPngFileName(
        expectedPrefix: String,
        fileName: String,
        before: Long,
        after: Long,
    ) {
        val timestamp =
            fileName
                .removePrefix(expectedPrefix)
                .removeSuffix(".png")
                .toLongOrNull()
        assertTrue(fileName.startsWith(expectedPrefix))
        assertTrue(fileName.endsWith(".png"))
        assertTrue(timestamp != null && timestamp in before..after)
    }

    @Test
    fun `normalizeShareUrl accepts http or https with host and rejects blank or relative urls`() {
        assertEquals("https://example.org/@alice", normalizeShareUrl("  https://example.org/@alice  "))
        assertEquals("http://example.org/@alice", normalizeShareUrl("http://example.org/@alice"))
        assertNull(normalizeShareUrl(null))
        assertNull(normalizeShareUrl(""))
        assertNull(normalizeShareUrl("   "))
        assertNull(normalizeShareUrl("/users/alice"))
        assertNull(normalizeShareUrl("//example.org/@alice"))
        assertNull(normalizeShareUrl("example.org/@alice"))
        assertNull(normalizeShareUrl("javascript:alert(1)"))
    }

    @Test
    fun `buildCrossPostIntent includes text extras for non-blank url and omits EXTRA_TEXT when blank`() {
        val context = RuntimeEnvironment.getApplication()
        val imageUri = Uri.parse("content://dev.dimension.flare.provider/cache/profile_share.png")

        val withUrl =
            buildCrossPostIntent(
                context = context,
                imageUri = imageUri,
                shareUrl = "  https://example.org/@alice  ",
            )
        assertEquals(Intent.ACTION_SEND, withUrl.action)
        assertEquals("image/png", withUrl.type)
        assertTrue(withUrl.hasExtra(Intent.EXTRA_TEXT))
        assertEquals("https://example.org/@alice", withUrl.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("\n\nhttps://example.org/@alice", withUrl.getStringExtra(ShortcutComposeActivity.EXTRA_INITIAL_TEXT))
        assertEquals(0, withUrl.getIntExtra(ShortcutComposeActivity.EXTRA_INITIAL_CURSOR_POSITION, -1))

        val withoutUrl =
            buildCrossPostIntent(
                context = context,
                imageUri = imageUri,
                shareUrl = "   ",
            )
        assertFalse(withoutUrl.hasExtra(Intent.EXTRA_TEXT))
        assertEquals("", withoutUrl.getStringExtra(ShortcutComposeActivity.EXTRA_INITIAL_TEXT))
        assertEquals(0, withoutUrl.getIntExtra(ShortcutComposeActivity.EXTRA_INITIAL_CURSOR_POSITION, -1))
    }
}
