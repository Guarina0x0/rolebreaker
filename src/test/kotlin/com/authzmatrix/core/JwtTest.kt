package com.authzmatrix.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwtTest {

    private fun tok(header: String, payload: String, sig: String = "sig") =
        "${Jwt.encode(header)}.${Jwt.encode(payload)}.$sig"

    @Test fun parsesAlgRoleOwnerSub() {
        val info = Jwt.parse(tok(
            """{"alg":"HS256","typ":"JWT"}""",
            """{"sub":"u1","preferred_username":"alice","role":"admin","exp":9999999999}""",
        ))!!
        assertEquals("HS256", info.alg)
        assertEquals("u1", info.sub)
        assertEquals("admin", info.role)
        assertEquals("alice", info.owner)
        assertFalse(info.isExpired())
    }

    @Test fun roleFromKeycloakRealmAccess() {
        val info = Jwt.parse(tok("""{"alg":"none"}""", """{"realm_access":{"roles":["admin","user"]}}"""))!!
        assertTrue(info.role!!.contains("admin"))
    }

    @Test fun expNullWhenMissingOrNonNumeric() {
        assertNull(Jwt.parse(tok("""{"alg":"HS256"}""", """{"sub":"x"}"""))!!.exp)
        assertNull(Jwt.parse(tok("""{"alg":"HS256"}""", """{"exp":"soon"}"""))!!.exp)
    }

    @Test fun detectsExpired() {
        assertTrue(Jwt.parse(tok("""{"alg":"HS256"}""", """{"exp":1000000000}"""))!!.isExpired())
    }

    @Test fun stripsBearerPrefix() {
        assertEquals("a", Jwt.parse("Bearer " + tok("""{"alg":"HS256"}""", """{"sub":"a"}"""))!!.sub)
    }

    @Test fun findAllExtractsFromBlob() {
        val t = tok("""{"alg":"HS256"}""", """{"sub":"a"}""")
        assertTrue(Jwt.findAll("Authorization: Bearer $t\r\nX: y").contains(t))
    }

    @Test fun rejectsNonJwt() {
        assertNull(Jwt.parse("hello.world"))
        assertNull(Jwt.parse("randomstring"))
    }
}
