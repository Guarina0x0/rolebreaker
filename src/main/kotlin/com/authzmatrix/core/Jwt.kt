package com.authzmatrix.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Base64

/**
 * A parsed JWT. We only read the (unverified) header + claims for display, role/owner
 * detection and a lightweight security assessment — we never validate the signature; this is
 * an auditing aid.
 */
class JwtInfo(
    val header: JSONObject,
    val payload: JSONObject,
    val signature: String,
) {
    val alg: String? = header.optString("alg", null)?.ifBlank { null }
    val typ: String? = header.optString("typ", null)?.ifBlank { null }

    val sub: String? = payload.optString("sub", null)?.ifBlank { null }
    val issuer: String? = payload.optString("iss", null)?.ifBlank { null }
    val audience: String? = stringify(payload.opt("aud"))
    val exp: Long? = (payload.opt("exp") as? Number)?.toLong()

    /** Best-effort owner: who this token belongs to. */
    val owner: String? = firstOf("name", "preferred_username", "email", "username", "unique_name", "upn", "sub")

    /** Best-effort role/authority, covering common shapes incl. Keycloak realm_access.roles. */
    val role: String? = extractRole()

    fun isExpired(skewSeconds: Long = 0): Boolean {
        val e = exp ?: return false
        return Instant.now().epochSecond >= (e - skewSeconds)
    }

    fun expInstant(): Instant? = exp?.let { Instant.ofEpochSecond(it) }

    val signed: Boolean get() = signature.isNotEmpty() && !alg.equals("none", ignoreCase = true)

    /** Short security assessment for the catalog. */
    fun securityNote(): String {
        val notes = mutableListOf<String>()
        val a = alg?.lowercase()
        when {
            a == null -> notes += "sin alg"
            a == "none" -> notes += "⚠ alg:none (sin firma)"
            a.startsWith("hs") -> notes += "HS simétrico (forjable si secreto débil)"
            a.startsWith("rs") || a.startsWith("es") || a.startsWith("ps") -> notes += a.uppercase()
            else -> notes += a.uppercase()
        }
        if (signature.isEmpty() && a != "none") notes += "⚠ sin firma"
        if (exp == null) notes += "sin exp"
        else if (isExpired()) notes += "⚠ EXPIRADO"
        return notes.joinToString("; ")
    }

    /** One-line summary for tooltips / lists. */
    fun summary(): String {
        val parts = mutableListOf<String>()
        role?.let { parts += "role=$it" }
        owner?.let { parts += "owner=$it" }
        expInstant()?.let { parts += if (isExpired()) "EXPIRED" else "exp=$it" }
        alg?.let { parts += "alg=$it" }
        return if (parts.isEmpty()) "(no standard claims)" else parts.joinToString("  ")
    }

    private fun extractRole(): String? {
        for (k in listOf("role", "roles", "authorities", "scope", "scp", "permissions", "groups")) {
            if (payload.has(k)) stringify(payload.opt(k))?.let { return it }
        }
        payload.optJSONObject("realm_access")?.let { ra ->
            if (ra.has("roles")) stringify(ra.opt("roles"))?.let { return it }
        }
        return null
    }

    private fun firstOf(vararg keys: String): String? {
        for (k in keys) payload.optString(k, null)?.ifBlank { null }?.let { return it }
        return null
    }

    private fun stringify(v: Any?): String? = when {
        v == null || v == JSONObject.NULL -> null
        v is JSONArray -> (0 until v.length()).joinToString(", ") { v.get(it).toString() }.ifBlank { null }
        else -> v.toString().ifBlank { null }
    }
}

object Jwt {

    private val JWT_REGEX = Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*""")

    /** Parse a compact JWS (header.payload.signature). Returns null if it isn't one. */
    fun parse(token: String): JwtInfo? {
        val t = token.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
        val segs = t.split(".")
        if (segs.size < 2) return null
        return try {
            val header = JSONObject(decode(segs[0]))
            val payload = JSONObject(decode(segs[1]))
            JwtInfo(header, payload, segs.getOrElse(2) { "" })
        } catch (_: Exception) {
            null
        }
    }

    fun looksLikeJwt(s: String): Boolean = JWT_REGEX.matches(s.trim())

    /** Extract every JWT-looking substring from an arbitrary blob (headers, cookies, body, …). */
    fun findAll(blob: String): List<String> =
        JWT_REGEX.findAll(blob).map { it.value }.distinct().toList()

    fun encode(json: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun decode(seg: String): String =
        String(Base64.getUrlDecoder().decode(padded(seg)), Charsets.UTF_8)

    private fun padded(seg: String): String {
        val rem = seg.length % 4
        return if (rem == 0) seg else seg + "=".repeat(4 - rem)
    }
}
