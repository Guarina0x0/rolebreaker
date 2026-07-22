package com.authzmatrix.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JwtForgeTest {

    private fun tok(header: String, payload: String, sig: String = "sig") =
        "${Jwt.encode(header)}.${Jwt.encode(payload)}.$sig"

    @Test fun algNoneIsParseableAndUnsigned() {
        val info = Jwt.parse(tok("""{"alg":"HS256","typ":"JWT"}""", """{"sub":"a","role":"user"}"""))!!
        val forged = JwtForge.algNone(info)
        val p = Jwt.parse(forged)!!
        assertEquals("none", p.alg)
        assertEquals("user", p.role)
        assertTrue(forged.endsWith("."))
    }

    @Test fun escalateRoleChangesClaimAndAlg() {
        val info = Jwt.parse(tok("""{"alg":"HS256"}""", """{"role":"user"}"""))!!
        val p = Jwt.parse(JwtForge.escalateRole(info, "role", "admin"))!!
        assertEquals("admin", p.role)
        assertEquals("none", p.alg)
    }

    @Test fun stripSignatureKeepsClaims() {
        val info = Jwt.parse(tok("""{"alg":"HS256"}""", """{"sub":"a"}""", "abc"))!!
        val s = JwtForge.stripSignature(info)
        assertTrue(s.endsWith("."))
        assertEquals("a", Jwt.parse(s)!!.sub)
    }

    @Test fun detectsRoleClaimKey() {
        val info = Jwt.parse(tok("""{"alg":"HS256"}""", """{"authorities":"X"}"""))!!
        assertEquals("authorities", JwtForge.roleClaimKey(info))
    }
}
