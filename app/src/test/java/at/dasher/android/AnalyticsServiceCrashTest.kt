package at.dasher.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crash-report helpers (RFC 0009). These are the pieces of the deferred-crash
 * reconstruction path that are testable without PostHog or the network — the
 * live `captureException` path is exercised manually via the debug Diagnostics
 * UI (see SettingsScreen.PrivacyContent).
 */
class AnalyticsServiceCrashTest {

    @Test
    fun parseStackTrace_readsClassMethodFileLine() {
        val printed = """
            java.lang.RuntimeException: boom
                at com.foo.Bar.baz(Bar.java:42)
                at com.foo.Qux.run(Qux.kt:7)
        """.trimIndent()

        val frames = AnalyticsService.parseStackTrace(printed)

        assertEquals(2, frames.size)
        assertEquals("com.foo.Bar", frames[0].className)
        assertEquals("baz", frames[0].methodName)
        assertEquals("Bar.java", frames[0].fileName)
        assertEquals(42, frames[0].lineNumber)
        assertEquals("com.foo.Qux", frames[1].className)
        assertEquals("Qux.kt", frames[1].fileName)
        assertEquals(7, frames[1].lineNumber)
    }

    @Test
    fun parseStackTrace_handlesNativeMethod() {
        val printed = "at com.foo.Bar.native(Native Method)"
        val frames = AnalyticsService.parseStackTrace(printed)
        assertEquals(1, frames.size)
        assertEquals(-2, frames[0].lineNumber) // Thread.NOT_SUPPORTED_INSTRUCTION-ish sentinel
    }

    @Test
    fun parseStackTrace_emptyInputFallsBackToPlaceholder() {
        val frames = AnalyticsService.parseStackTrace("not a stack trace")
        assertTrue("expected at least one frame", frames.isNotEmpty())
    }

    @Test
    fun scrub_stripsUnixAndWindowsHomePaths() {
        val raw = "at com.foo.Bar.open(/Users/jane/secret.txt) at C:\\Users\\bob\\x.txt"
        val out = AnalyticsService.scrub(raw)
        assertTrue("unix home stripped", out.contains("/Users/<user>"))
        assertTrue("windows home stripped", out.contains("C:\\Users\\<user>"))
        assertTrue("no PII left", !out.contains("jane") && !out.contains("bob"))
    }

    @Test
    fun scrub_stripsEmails() {
        val raw = "contact: user@example.com, other.name+tag@sub.example.org"
        val out = AnalyticsService.scrub(raw)
        assertTrue(out.contains("<email>"))
        assertTrue("emails gone", !out.contains("user@example.com") && !out.contains("sub.example.org"))
    }
}
