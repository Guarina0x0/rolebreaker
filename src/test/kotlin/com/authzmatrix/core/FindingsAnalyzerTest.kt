package com.authzmatrix.core

import com.authzmatrix.model.AccessResult
import com.authzmatrix.model.ActivityEntry
import com.authzmatrix.model.MatrixRow
import com.authzmatrix.model.Persona
import com.authzmatrix.model.Severity
import com.authzmatrix.model.Verdict
import kotlin.test.Test
import kotlin.test.assertTrue

class FindingsAnalyzerTest {

    private fun storeWith(vararg personas: Persona) = PersonaStore().apply {
        this.personas.clear()
        this.personas.addAll(personas)
    }

    @Test fun anonAccessIsCritical() {
        val admin = Persona("admin")
        val anon = Persona("anon", anonymous = true)
        val store = storeWith(admin, anon)
        val row = MatrixRow("GET /x", "GET", "http://h/x", null, 200, 100)
        row.results[admin.id] = AccessResult(admin.id, 200, 100, Verdict.SAME_AS_BASELINE, null)
        row.results[anon.id] = AccessResult(anon.id, 200, 100, Verdict.ALLOWED, null)

        val f = FindingsAnalyzer.analyze(listOf(row), emptyList(), store)
        assertTrue(f.any { it.type == "Acceso anónimo" && it.severity == Severity.CRITICAL })
    }

    @Test fun differentialAccessFlagged() {
        val admin = Persona("admin")
        val user = Persona("user")
        val store = storeWith(admin, user)
        val row = MatrixRow("GET /adm", "GET", "http://h/adm", null, 200, 100)
        row.results[admin.id] = AccessResult(admin.id, 200, 100, Verdict.ALLOWED, null)
        row.results[user.id] = AccessResult(user.id, 403, 50, Verdict.DENIED, null)

        val f = FindingsAnalyzer.analyze(listOf(row), emptyList(), store)
        assertTrue(f.any { it.type == "Acceso diferencial" })
    }

    @Test fun idorVariantFlaggedAsHorizontal() {
        val user = Persona("user")
        val store = storeWith(user)
        val row = MatrixRow("GET /u [id:1→2]", "GET", "http://h/u/2", null, 200, 100)
        row.results[user.id] = AccessResult(user.id, 200, 100, Verdict.ALLOWED, null)

        val f = FindingsAnalyzer.analyze(listOf(row), emptyList(), store)
        assertTrue(f.any { it.type == "IDOR / acceso horizontal" })
    }

    @Test fun forgedJwtAcceptedFromActivity() {
        val store = storeWith(Persona("user"))
        val e = ActivityEntry("00:00:00", "🔴 JWT:none", "GET", "http://h/x", 200, 100, Verdict.ALLOWED, null)

        val f = FindingsAnalyzer.analyze(emptyList(), listOf(e), store)
        assertTrue(f.any { it.type == "JWT forjado aceptado" && it.severity == Severity.CRITICAL })
    }
}
