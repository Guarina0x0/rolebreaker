package com.authzmatrix.core

import java.time.Instant
import kotlin.test.Test
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
}
