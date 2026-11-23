package dev.dimension.flare.ui.screen.share

import android.content.Intent
import android.net.Uri
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
@Config(sdk = [35], manifest = Config.NONE)
class ShareCardSheetHelperTest {
    @Test
    fun `crossPostInitialText formats non-blank url and stays empty for null or blank`() {
        assertEquals("\n\nhttps://example.org/@alice", crossPostInitialText("https://example.org/@alice"))
        assertEquals("\n\nhttps://example.org/@alice", crossPostInitialText("  https://example.org/@alice  "))
        assertEquals("", crossPostInitialText(null))
        assertEquals("", crossPostInitialText(""))
        assertEquals("", crossPostInitialText("   "))
    }

    @Test
    fun `shareFileName uses provided prefix and sanitizes key`() {
        assertEquals("profile_alice_example.org.png", shareImageFileName("profile", "alice/example.org"))
        assertEquals("status_123_example.org.png", shareImageFileName("status", "123/example.org"))
    }

    @Test
    fun `normalizeShareUrl trims non-blank urls and returns null for null or blank`() {
        assertEquals("https://example.org/@alice", normalizeShareUrl("  https://example.org/@alice  "))
        assertNull(normalizeShareUrl(null))
        assertNull(normalizeShareUrl(""))
        assertNull(normalizeShareUrl("   "))
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
