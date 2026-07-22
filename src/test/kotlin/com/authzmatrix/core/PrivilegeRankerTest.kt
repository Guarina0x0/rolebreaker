package com.authzmatrix.core

import com.authzmatrix.model.Persona
import kotlin.test.Test
import kotlin.test.assertTrue

class PrivilegeRankerTest {

    @Test fun adminScoresAboveUser() =
        assertTrue(PrivilegeRanker.score("admin") > PrivilegeRanker.score("user"))

    @Test fun userScoresAboveGuest() =
        assertTrue(PrivilegeRanker.score("usuario") > PrivilegeRanker.score("invitado"))

    @Test fun unknownRoleIsMid() {
        val s = PrivilegeRanker.score("qwerty-role")
        assertTrue(s in 31..59)
    }

    @Test fun assignLevelsOrdersMostPrivilegedFirst() {
        val a = Persona("administrador"); val u = Persona("usuario"); val g = Persona("guest")
        PrivilegeRanker.assignLevels(listOf(g, u, a)) // deliberately unordered
        assertTrue(a.level < u.level)
        assertTrue(u.level < g.level)
    }

    @Test fun anonymousIsAlwaysLowest() {
        val admin = Persona("admin"); val anon = Persona("anon", anonymous = true)
        PrivilegeRanker.assignLevels(listOf(anon, admin))
        assertTrue(admin.level < anon.level)
    }
}
