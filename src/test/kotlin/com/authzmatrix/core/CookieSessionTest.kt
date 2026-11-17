package com.authzmatrix.core

import com.authzmatrix.model.Persona
import com.authzmatrix.model.TokenLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CookieSessionTest {

    @Test fun parsesCookieHeaderIncludingEqualsInValues() {
        val cookies = CookieHeader.parse("Cookie: session=abc; csrf=x=y; lang=es")
        assertEquals("abc", cookies["session"])
        assertEquals("x=y", cookies["csrf"])
        assertEquals("es", cookies["lang"])
    }

    @Test fun serializesAndCanonicalises() {
        val cookies = linkedMapOf("session" to "abc", "csrf" to "xyz")
        assertEquals("session=abc; csrf=xyz", CookieHeader.serialize(cookies))
        assertEquals("csrf=xyz; session=abc", CookieHeader.canonical(cookies))
    }

    @Test fun parsesSetCookieAttributes() {
        assertEquals("abc" to "session", CookieHeader.parseSetCookie("session=abc; Path=/; HttpOnly")?.let { it.second to it.first })
        val merged = CookieHeader.fromSetCookieHeaders(listOf("a=1; Path=/", "b=2"))
        assertEquals(mapOf("a" to "1", "b" to "2"), merged)
    }

    @Test fun recognisesAuthCookiesByNameOrJwtValue() {
        assertTrue(CookieSession.isAuthCookie("PHPSESSID", "opaque"))
        assertTrue(CookieSession.isAuthCookie("my_app_sess", "opaque"))
        assertTrue(CookieSession.isAuthCookie("foo", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhIn0.sig"))
        assertFalse(CookieSession.isAuthCookie("lang", "es"))
        assertFalse(CookieSession.isAuthCookie("theme", "dark"))
    }

    @Test fun identitySubsetExcludesRotatingCsrfCookies() {
        val cookies = linkedMapOf("session" to "s", "csrf" to "c", "lang" to "es")
        assertEquals(mapOf("session" to "s"), CookieSession.identitySubset(cookies))
    }

    @Test fun replaceAuthCookiesKeepsUnrelatedCookies() {
        val existing = mapOf("session" to "old", "csrf" to "old-csrf", "lang" to "es")
        val persona = mapOf("session" to "new", "csrf" to "new-csrf")
        val merged = CookieHeader.replaceAuthCookies(existing, persona)
        assertEquals("new", merged["session"])
        assertEquals("new-csrf", merged["csrf"])
        assertEquals("es", merged["lang"])
    }

    @Test fun stripAuthCookiesRemovesConfiguredNamesToo() {
        val existing = mapOf("session" to "s", "csrf" to "c", "lang" to "es", "custom" to "x")
        val stripped = CookieHeader.stripAuthCookies(existing, setOf("custom"))
        assertEquals(mapOf("lang" to "es"), stripped)
    }

    @Test fun matchesCookiePersonaByMostMatchingCookies() {
        val admin = Persona("admin", location = TokenLocation.COOKIE).apply {
            cookies["session"] = "a"
            cookies["csrf"] = "c1"
        }
        val user = Persona("user", location = TokenLocation.COOKIE).apply {
            cookies["session"] = "b"
            cookies["csrf"] = "c2"
        }
        val request = mapOf("session" to "b", "csrf" to "c2", "lang" to "es")
        assertEquals(user, CookieSession.matchPersona(request, listOf(admin, user)))
    }

    @Test fun matchesLegacySingleCookiePersona() {
        val legacy = Persona("legacy", token = "abc", location = TokenLocation.COOKIE, cookieName = "sid")
        assertEquals(legacy, CookieSession.matchPersona(mapOf("sid" to "abc"), listOf(legacy)))
    }

    @Test fun doesNotMatchAnonymousOrHeaderPersonas() {
        val anon = Persona("anon", anonymous = true)
        val bearer = Persona("bearer", token = "t")
        assertNull(CookieSession.matchPersona(mapOf("session" to "x"), listOf(anon, bearer)))
    }
}
