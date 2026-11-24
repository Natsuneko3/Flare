package dev.dimension.flare.ui.screen.serviceselect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebViewLoginScreenTest {
    @Test
    fun parseDesktopJavaScriptStringDecodesJsonStringsAndDropsEmptyValues() {
        assertEquals(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15",
            parseDesktopJavaScriptString("\"Mozilla\\/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit\\/605.1.15\""),
        )
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64)",
            parseDesktopJavaScriptString("Mozilla/5.0 (X11; Linux x86_64)"),
        )
        assertNull(parseDesktopJavaScriptString(""))
        assertNull(parseDesktopJavaScriptString("   "))
        assertNull(parseDesktopJavaScriptString("null"))
        assertNull(parseDesktopJavaScriptString("undefined"))
        assertNull(parseDesktopJavaScriptString("\"\""))
        assertNull(parseDesktopJavaScriptString("\"   \""))
        assertNull(parseDesktopJavaScriptString("\"unterminated"))
    }
}
