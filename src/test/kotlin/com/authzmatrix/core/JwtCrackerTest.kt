package com.authzmatrix.core

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JwtCrackerTest {

    private fun signedHs256(payload: String, secret: String): String {
        val h = Jwt.encode("""{"alg":"HS256","typ":"JWT"}""")
        val p = Jwt.encode(payload)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val sig = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("$h.$p".toByteArray(Charsets.UTF_8)))
        return "$h.$p.$sig"
    }

    @Test fun findsCommonSecret() {
        val t = signedHs256("""{"sub":"a"}""", "secret")
        assertEquals("secret", JwtCracker.crack(t, JwtCracker.COMMON_SECRETS))
    }

    @Test fun nullWhenSecretNotInList() {
        val t = signedHs256("""{"sub":"a"}""", "zzz-not-in-any-list-91827")
        assertNull(JwtCracker.crack(t, JwtCracker.COMMON_SECRETS))
    }

    @Test fun findsFromCustomWordlist() {
        val t = signedHs256("""{"sub":"a"}""", "weirdo")
        assertEquals("weirdo", JwtCracker.crack(t, listOf("nope", "weirdo", "other")))
    }

    @Test fun nonHmacTokenReturnsNull() {
        val t = "${Jwt.encode("""{"alg":"none"}""")}.${Jwt.encode("""{"sub":"a"}""")}."
        assertNull(JwtCracker.crack(t, listOf("secret")))
    }
}
