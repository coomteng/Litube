package com.hhst.youtubelite.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    @Test fun keepsTrafficChallengesInsideTheAppWithoutPageScripts() {
        assertTrue(UrlPolicy.canLoad("https://www.google.com/sorry/index?continue=https%3A%2F%2Fm.youtube.com"))
        assertTrue(UrlPolicy.canLoad("https://m.youtube.com/sorry/"))
        assertFalse(UrlPolicy.shouldInject("https://m.youtube.com/sorry/"))
        assertFalse(UrlPolicy.canLoad("https://www.google.com/search?q=test"))
        assertFalse(UrlPolicy.canLoad("https://www.google.com.evil.example/sorry/"))
        assertFalse(UrlPolicy.canLoad("https://user@www.google.com/sorry/"))
        assertFalse(UrlPolicy.canLoad("http://www.google.com/sorry/"))
        assertFalse(UrlPolicy.canLoad("https://www.google.com:444/sorry/"))
    }

    @Test
    fun allowsYoutubeAndAccounts() {
        assertTrue(UrlPolicy.isAllowedUrl("https://m.youtube.com"))
        assertTrue(UrlPolicy.isAllowedUrl("https://www.youtube.com/watch?v=abc"))
        assertTrue(UrlPolicy.isAllowedUrl("https://accounts.google.com/ServiceLogin"))
    }

    @Test
    fun canLoadLocalSchemes() {
        assertTrue(UrlPolicy.canLoad("about:blank"))
    }

    @Test
    fun rejectsOtherHosts() {
        assertFalse(UrlPolicy.isAllowedUrl("https://example.com"))
        assertFalse(UrlPolicy.canLoad("https://evil.example"))
    }

    @Test
    fun externalLaunch_needsGestureAndWebOrIntentScheme() {
        // Off-allowlist web link the user tapped: hand to the browser.
        assertTrue(UrlPolicy.mayOpenExternally("https://example.com/x", hasGesture = true))
        assertTrue(UrlPolicy.mayOpenExternally("intent://watch?v=a#Intent;scheme=vnd.youtube;end", hasGesture = true))
        // Same links fired by script: swallowed.
        assertFalse(UrlPolicy.mayOpenExternally("https://example.com/x", hasGesture = false))
        assertFalse(UrlPolicy.mayOpenExternally("intent://x#Intent;end", hasGesture = false))
        // Non-web schemes never leave the app, gesture or not.
        for (url in listOf("market://details?id=x", "tel:+41000000000", "sms:1", "vnd.youtube:abc", "mailto:a@b", "file:///etc/hosts")) {
            assertFalse(url, UrlPolicy.mayOpenExternally(url, hasGesture = true))
        }
    }
}
