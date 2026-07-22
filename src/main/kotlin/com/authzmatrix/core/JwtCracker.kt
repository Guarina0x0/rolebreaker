package com.authzmatrix.core

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Offline HMAC secret check for HS256/384/512 tokens. If a common (or wordlist) secret verifies
 * the signature, the token is forgeable → the JWT is effectively insecure.
 */
object JwtCracker {

    /** Small built-in list of frequently-seen JWT secrets. */
    val COMMON_SECRETS: List<String> = listOf(
        "secret", "secretkey", "secret_key", "mysecret", "supersecret", "s3cr3t", "secret123",
        "password", "password123", "changeme", "123456", "1234567890", "qwerty", "letmein",
        "jwt", "jwtsecret", "jwt_secret", "jwtkey", "token", "key", "private", "privatekey",
        "hmac", "signature", "admin", "root", "test", "dev", "prod", "default", "welcome",
        "your-256-bit-secret", "your_jwt_secret", "your-secret-key", "my-secret-key",
        "supersecretkey", "topsecret", "0000", "shhhh", "ninja", "keyboardcat",
    )

    fun macAlgFor(alg: String?): String? = when (alg?.uppercase()) {
        "HS256" -> "HmacSHA256"
        "HS384" -> "HmacSHA384"
        "HS512" -> "HmacSHA512"
        else -> null
    }

    fun isHmac(alg: String?): Boolean = macAlgFor(alg) != null

    /** Try each secret against an HS* token. Returns the working secret, or null if none match. */
    fun crack(token: String, secrets: Iterable<String>): String? {
        val parts = token.trim().removePrefix("Bearer ").trim().split(".")
        if (parts.size < 3 || parts[2].isEmpty()) return null
        val macAlg = macAlgFor(Jwt.parse(token)?.alg) ?: return null
        val signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8)
        val expected = parts[2]
        for (s in secrets) {
            if (s.isNotEmpty() && sign(macAlg, s, signingInput) == expected) return s
        }
        return null
    }

    private fun sign(macAlg: String, secret: String, data: ByteArray): String {
        val mac = Mac.getInstance(macAlg)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), macAlg))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data))
    }
}
