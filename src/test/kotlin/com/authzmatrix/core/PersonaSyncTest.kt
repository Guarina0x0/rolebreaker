package com.authzmatrix.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonaSyncTest {

    private fun tok(role: String, sub: String, exp: Long) =
        "${Jwt.encode("""{"alg":"HS256"}""")}." +
            "${Jwt.encode("""{"sub":"$sub","role":"$role","exp":$exp}""")}.s"

    @Test fun onlyRolesWithActiveTokenBecomePersonas() {
        val store = PersonaStore()
        val future = Instant.now().epochSecond + 3600
        val past = Instant.now().epochSecond - 3600
        store.capture(tok("admin", "a", future), "src") // active
        store.capture(tok("user", "u", past), "src")    // expired only

        store.syncPersonasFromTokens()
        val names = store.personas.filter { !it.anonymous }.map { it.name }
        assertTrue(names.contains("admin"), "active role should create a persona")
        assertFalse(names.contains("user"), "expired-only role must be skipped")
    }

    @Test fun freshestActiveTokenWins() {
        val store = PersonaStore()
        val soon = Instant.now().epochSecond + 600
        val later = Instant.now().epochSecond + 7200
        store.capture(tok("admin", "a", soon), "src")
        store.capture(tok("admin", "a", later), "src")

        store.syncPersonasFromTokens()
        val admin = store.personas.first { it.name == "admin" }
        assertTrue(Jwt.parse(admin.token)?.exp == later, "persona should hold the freshest active token")
    }

    @Test fun anonymousPersonaIsAddedOnceAndRanksLowest() {
        val store = PersonaStore()
        val future = Instant.now().epochSecond + 3600
        store.capture(tok("admin", "a", future), "src")
        store.syncPersonasFromTokens()

        assertTrue(store.ensureAnonymousPersona(), "first call should create the anon persona")
        assertFalse(store.ensureAnonymousPersona(), "second call must not duplicate it")
        val anon = store.personas.single { it.anonymous }
        assertTrue(anon.enabled)

        PrivilegeRanker.assignLevels(store.personas)
        val admin = store.personas.first { !it.anonymous }
        assertTrue(admin.level < anon.level, "anonymous must rank below every authenticated role")
    }

    @Test fun capturedTokenCatalogIsBounded() {
        val store = PersonaStore()
        val future = Instant.now().epochSecond + 3600
        repeat(PersonaStore.MAX_CAPTURED_TOKENS + 5) { i ->
            store.capture(tok("role$i", "sub$i", future), "src")
        }
        assertTrue(store.capturedTokens.size <= PersonaStore.MAX_CAPTURED_TOKENS)
        assertTrue(store.capturedTokens.isNotEmpty())
    }

    @Test fun jwtInsideCookieCreatesCookiePersona() {
        val store = PersonaStore()
        val future = Instant.now().epochSecond + 3600
        val token = tok("admin", "a", future)
        store.capture(token, "src")
        store.captureCookie(mapOf("session" to token, "csrf" to "csrf-value"), "src")

        store.syncPersonasFromTokens()
        val admin = store.personas.single { it.name == "admin" }
        assertTrue(admin.usesCookies, "JWT-in-cookie should become a cookie persona")
        assertEquals(token, admin.cookies["session"])
        assertTrue(admin.token.isEmpty(), "the cookie bundle carries the JWT")
    }
}
