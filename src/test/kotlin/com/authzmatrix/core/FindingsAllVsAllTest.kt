package com.authzmatrix.core

import com.authzmatrix.model.AccessResult
import com.authzmatrix.model.MatrixRow
import com.authzmatrix.model.Persona
import com.authzmatrix.model.Verdict
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindingsAllVsAllTest {

    private fun storeWith(vararg personas: Persona) = PersonaStore().apply {
        this.personas.clear(); this.personas.addAll(personas)
    }

    @Test fun identicalResponseBetweenRolesFlagged() {
        val a = Persona("admin"); val b = Persona("user")
        val store = storeWith(a, b)
        val row = MatrixRow("GET /me", "GET", "http://h/me?user_id=1", null, 200, 100)
        row.results[a.id] = AccessResult(a.id, 200, 100, Verdict.ALLOWED, null, bodyHash = 777)
        row.results[b.id] = AccessResult(b.id, 200, 100, Verdict.ALLOWED, null, bodyHash = 777)

        val f = FindingsAnalyzer.analyze(listOf(row), emptyList(), store)
        assertTrue(f.any { it.type == I18n.t("find.identical") })
    }

    @Test fun lowerPrivAccessFlaggedEvenWithoutDenied() {
        val user = Persona("user")
        val store = storeWith(user)
        val row = MatrixRow("${FindingsAnalyzer.LOWER_PRIV_MARKER} GET /admin", "GET", "http://h/admin", null, 200, 100)
        row.results[user.id] = AccessResult(user.id, 200, 100, Verdict.ALLOWED, null)

        val f = FindingsAnalyzer.analyze(listOf(row), emptyList(), store)
        assertTrue(f.any { it.type == I18n.t("find.lowerPriv") })
    }

    @Test fun publicResponseEqualToAnonNotFlagged() {
        val a = Persona("admin"); val b = Persona("user"); val anon = Persona("anon", anonymous = true)
        val store = storeWith(a, b, anon)
        val row = MatrixRow("GET /pub", "GET", "http://h/pub", null, 200, 50)
        row.results[a.id] = AccessResult(a.id, 200, 50, Verdict.ALLOWED, null, bodyHash = 9)
        row.results[b.id] = AccessResult(b.id, 200, 50, Verdict.ALLOWED, null, bodyHash = 9)
        row.results[anon.id] = AccessResult(anon.id, 200, 50, Verdict.ALLOWED, null, bodyHash = 9)

        val f = FindingsAnalyzer.analyze(listOf(row), emptyList(), store)
        assertFalse(f.any { it.type == I18n.t("find.identical") })
    }
}
