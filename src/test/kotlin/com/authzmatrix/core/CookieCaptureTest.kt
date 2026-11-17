package com.authzmatrix.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CookieCaptureTest {

    @Test fun capturesCookieSession() {
        val store = PersonaStore()
        assertTrue(store.captureCookie(mapOf("session" to "a", "lang" to "es"), "src"))
        val captured = store.capturedTokens.single { it.isCookie }
        assertEquals("a", captured.cookies["session"])
        assertEquals("es", captured.cookies["lang"])
    }

    @Test fun dedupesByAuthSubsetAndMergesNewNonAuthCookies() {
        val store = PersonaStore()
        assertTrue(store.captureCookie(mapOf("session" to "a", "lang" to "es"), "src1"))
        assertFalse(store.captureCookie(mapOf("session" to "a", "lang" to "en", "csrf" to "c"), "src2"))

        val captured = store.capturedTokens.single { it.isCookie }
        assertEquals("a", captured.cookies["session"])
        assertEquals("en", captured.cookies["lang"])
        assertEquals("c", captured.cookies["csrf"])
    }

    @Test fun differentSessionsAreSeparateEntries() {
        val store = PersonaStore()
        assertTrue(store.captureCookie(mapOf("session" to "a"), "src"))
        assertTrue(store.captureCookie(mapOf("session" to "b"), "src"))
        assertEquals(2, store.capturedTokens.count { it.isCookie })
    }

    @Test fun capturesCustomCookieBundlesWithoutAuthNames() {
        val store = PersonaStore()
        assertTrue(store.captureCookie(mapOf("lang" to "es", "theme" to "dark"), "src"))
        assertEquals(1, store.capturedTokens.count { it.isCookie })
        assertEquals("dark", store.capturedTokens.single { it.isCookie }.cookies["theme"])
    }
}
